package org.example;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.cart.dto.AddToCartRequest;
import org.example.cart.dto.UpdateCartItemRequest;
import org.example.cart.service.CartService;
import org.example.common.BusinessException;
import org.example.food.dto.FoodRequest;
import org.example.food.repository.FoodRepository;
import org.example.food.service.FoodService;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.example.order.dto.CreateOrderRequest;
import org.example.order.entity.*;
import org.example.order.service.OrderService;
import org.example.shared.api.Role;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.example.OrderServiceTest.CUSTOMER;
import static org.example.OrderServiceTest.EMPLOYEE;
import static org.example.OrderServiceTest.assertCode;
import static org.example.OrderServiceTest.dineIn;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrderIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_integration").withInitScript("db/schema.sql");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        TestAuthKeys.register(properties);
    }

    @Autowired JdbcTemplate db;
    @Autowired OrderService service;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean CartService carts;
    @Autowired FoodService foodService;
    @Autowired FoodRepository foodRepository;

    @BeforeEach void seed() {
        // This database belongs solely to this ephemeral test container.
        for (String table : List.of("order_items", "orders", "cart_items", "carts", "foods", "categories", "users"))
            db.update("DELETE FROM " + table);
        new ResourceDatabasePopulator(new ClassPathResource("db/seed.sql"))
                .execute(Objects.requireNonNull(db.getDataSource()));
    }

    /** Spy thật nằm sau proxy @Transactional; stub trên target để giao dịch vẫn bao quanh lời gọi. */
    private CartService cartTarget() {
        return AopTestUtils.getUltimateTargetObject(carts);
    }

    @Test void checkoutPersistsSnapshotsUpdatesStockAndClearsCart() {
        int originalStock = stock(1);
        long originalFoodVersion = foodVersion(1);
        int originalCartVersion = db.queryForObject("SELECT version FROM carts WHERE id = 1", Integer.class);
        var order = service.create(CUSTOMER, dineIn());
        assertEquals(OrderStatus.PENDING, order.status());
        assertEquals("B05", order.tableNumber());
        assertEquals("Ít cay", order.note());
        assertEquals(List.of(1L, 2L, 14L), order.items().stream().map(item -> item.foodId()).toList());
        int quantity = order.items().stream().filter(item -> item.foodId() == 1).findFirst().orElseThrow().quantity();
        assertEquals(originalStock - quantity, stock(1));
        assertEquals(originalFoodVersion + 1, foodVersion(1));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM carts WHERE id = 1", Integer.class));
        assertEquals(originalCartVersion + 1, db.queryForObject("SELECT version FROM carts WHERE id = 1", Integer.class));
        assertEquals(0, order.totalAmount().compareTo(db.queryForObject(
                "SELECT total_amount FROM order_totals WHERE order_id = ?", BigDecimal.class, order.id())));
        assertEquals("DINE_IN", db.queryForObject("SELECT order_type FROM orders WHERE id = ?", String.class, order.id()));
        assertEquals(0L, db.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, order.id()));
        db.update("UPDATE foods SET name = 'Tên mới sau checkout', price = price + 1000 WHERE id = 1");
        var read = service.get(CUSTOMER, Role.CUSTOMER, order.id());
        assertEquals(order.items(), read.items());
        assertEquals(order.totalAmount(), read.totalAmount());
        assertEquals(order.createdAt(), read.createdAt());
    }

    @Test void adminFoodEditLosesToCommittedCheckoutInsteadOfOverwritingStock() {
        int originalStock = stock(1);
        var edit = new FoodRequest(1L, "Phở bò tái", null, new BigDecimal("70000"), null, 100, null);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> transactions.executeWithoutResult(status -> {
            foodRepository.findById(1L).orElseThrow(); // admin đã đọc món với version cũ
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> service.create(CUSTOMER, dineIn())).get(15, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            foodService.updateFood(1L, edit);
        }));
        assertEquals(originalStock - 2, stock(1));
        assertEquals(0, new BigDecimal("65000.00").compareTo(
                db.queryForObject("SELECT price FROM foods WHERE id = 1", BigDecimal.class)));
    }

    @Test void timestampsAreStoredAsUtcWallClock() {
        assertEquals("+00:00", db.queryForObject("SELECT @@session.time_zone", String.class));
        var order = service.create(CUSTOMER, dineIn());
        var stored = db.queryForObject("SELECT created_at FROM orders WHERE id = ?", LocalDateTime.class, order.id());
        assertEquals(order.createdAt(), stored.toInstant(ZoneOffset.UTC));
        assertTrue(Duration.between(stored.toInstant(ZoneOffset.UTC), Instant.now()).abs().toMinutes() < 2,
                "created_at phải là giờ UTC hiện tại, không lệch theo múi giờ JVM");
        var confirmed = service.confirm(EMPLOYEE, order.id());
        var confirmedAt = db.queryForObject("SELECT confirmed_at FROM orders WHERE id = ?", LocalDateTime.class, order.id());
        assertEquals(confirmed.confirmedAt(), confirmedAt.toInstant(ZoneOffset.UTC));
    }

    @Test void cartClearFailureRollsBackHeaderItemsStockAndCart() {
        int originalStock = stock(1);
        int originalCartItems = db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class);
        long originalVersion = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("Synthetic cart failure");
        }).when(cartTarget()).clearCart(anyLong());
        assertThrows(IllegalStateException.class, () -> service.create(CUSTOMER, dineIn()));
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(16, db.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(originalStock, stock(1));
        assertEquals(originalCartItems, db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class));
        assertEquals(originalVersion, db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class));
    }

    @Test void unavailableOrMissingFoodRollsBackEarlierReservations() {
        int stock1 = stock(1);
        db.update("UPDATE foods SET status = 'INACTIVE' WHERE id = 14");
        assertCode("FOOD_UNAVAILABLE", () -> service.create(CUSTOMER, dineIn()));
        assertEquals(stock1, stock(1));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class));
        db.update("UPDATE foods SET status = 'ACTIVE', stock_quantity = 1 WHERE id = 14");
        assertCode("INSUFFICIENT_STOCK", () -> service.create(CUSTOMER, dineIn()));
        assertEquals(stock1, stock(1));
        assertEquals(1, stock(14));
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test void customerScopeEmployeeFiltersAndBothServingFlowsUseRealMysql() {
        var own = service.list(CUSTOMER, Role.CUSTOMER, null, null, 0, 20);
        assertTrue(own.content().stream().allMatch(order -> order.userId() == 4));
        assertEquals(db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = 4", Long.class), own.totalElements());
        assertEquals(own.totalElements(), own.content().size());
        var newestFirst = own.content().stream().map(order -> order.createdAt()).toList();
        assertEquals(newestFirst.stream().sorted(java.util.Comparator.reverseOrder()).toList(), newestFirst);
        var active = service.list(EMPLOYEE, Role.EMPLOYEE, null, null, 0, 20);
        assertEquals(5, active.totalElements());
        assertTrue(active.content().stream().noneMatch(order ->
                order.status() == OrderStatus.COMPLETED || order.status() == OrderStatus.CANCELLED));
        assertTrue(service.list(EMPLOYEE, Role.EMPLOYEE, OrderStatus.SHIPPING, OrderType.DINE_IN, 0, 20).content().isEmpty());
        assertTrue(service.list(EMPLOYEE, Role.EMPLOYEE, null, OrderType.DELIVERY, 0, 20).content().stream()
                .allMatch(order -> order.orderType() == OrderType.DELIVERY));
        var page = service.list(CUSTOMER, Role.CUSTOMER, null, null, 0, 1);
        assertEquals(1, page.content().size());
        assertEquals(own.totalElements(), page.totalElements());
        var second = service.list(CUSTOMER, Role.CUSTOMER, null, null, 1, 1);
        assertEquals(own.content().get(1).id(), second.content().getFirst().id());
        assertTrue(service.list(CUSTOMER, Role.CUSTOMER, null, null, 1000, 20).content().isEmpty());
        assertEquals(OrderStatus.PROCESSING, service.updateStatus(EMPLOYEE, 1002, OrderStatus.PROCESSING).status());
        assertCode("INVALID_ORDER_TRANSITION", () -> service.updateStatus(EMPLOYEE, 1002, OrderStatus.SHIPPING));
        assertEquals(OrderStatus.COMPLETED, service.updateStatus(EMPLOYEE, 1002, OrderStatus.COMPLETED).status());
        long version1008 = db.queryForObject("SELECT version FROM orders WHERE id = 1008", Long.class);
        var confirmed = service.confirm(EMPLOYEE, 1008);
        assertEquals(2L, confirmed.confirmedBy());
        assertNotNull(confirmed.confirmedAt());
        service.updateStatus(EMPLOYEE, 1008, OrderStatus.PROCESSING);
        service.updateStatus(EMPLOYEE, 1008, OrderStatus.SHIPPING);
        assertNotNull(service.updateStatus(EMPLOYEE, 1008, OrderStatus.COMPLETED).completedAt());
        assertEquals("COMPLETED", db.queryForObject("SELECT status FROM orders WHERE id = 1008", String.class));
        assertEquals(2L, db.queryForObject("SELECT confirmed_by FROM orders WHERE id = 1008", Long.class));
        assertEquals(version1008 + 4, db.queryForObject("SELECT version FROM orders WHERE id = 1008", Long.class));
    }

    @Test void employeeCancellationKeepsConfirmationAndRollsBackIfSavingFails() {
        int originalStock = stock(13);
        var cancelled = service.updateStatus(EMPLOYEE, 1003, OrderStatus.CANCELLED);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.confirmedBy());
        assertNotNull(cancelled.confirmedAt());
        assertNotNull(cancelled.cancelledAt());
        assertEquals(originalStock + 2, stock(13));
        // A failed write after restoration must roll back both stock and status.
        int before = stock(1);
        assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> {
            service.cancel(CUSTOMER, 1001);
            throw new IllegalStateException("Synthetic failure after save");
        }));
        assertEquals(before, stock(1));
        assertEquals(OrderStatus.PENDING, service.get(CUSTOMER, Role.CUSTOMER, 1001).status());
    }

    @Test void cancellationRestoresStockOfFoodThatStoppedSelling() {
        int originalStock = stock(1);
        db.update("UPDATE foods SET status = 'INACTIVE' WHERE id = 1");
        service.cancel(CUSTOMER, 1001);
        assertEquals(originalStock + 2, stock(1));
        assertEquals("CANCELLED", db.queryForObject("SELECT status FROM orders WHERE id = 1001", String.class));
    }

    @Test void concurrentCancellationRestoresStockExactlyOnce() throws Exception {
        int originalStock = stock(1);
        Callable<Boolean> cancel = () -> {
            try { service.cancel(CUSTOMER, 1001); return true; }
            catch (BusinessException error) { assertEquals("INVALID_ORDER_TRANSITION", error.code()); return false; }
        };
        assertExactlyOneSucceeds(race(cancel, cancel));
        assertEquals(originalStock + 2, stock(1));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = 1001", Integer.class));
    }

    @Test void concurrentCheckoutCreatesOnlyOneOrderFromSameCart() throws Exception {
        int originalStock = stock(1);
        Callable<Boolean> create = () -> {
            try { service.create(CUSTOMER, dineIn()); return true; }
            catch (BusinessException error) { assertEquals("EMPTY_CART", error.code()); return false; }
        };
        assertExactlyOneSucceeds(race(create, create));
        assertEquals(9, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(originalStock - 2, stock(1));
    }

    @Test void missingOrEmptyCartsDoNotCreateOrders() {
        db.update("DELETE FROM carts WHERE user_id = 6");
        assertCode("CART_NOT_FOUND", () -> service.create(6, dineIn()));
        db.update("DELETE FROM cart_items WHERE cart_id = 1");
        assertCode("EMPTY_CART", () -> service.create(CUSTOMER, dineIn()));
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test void cartCrudFeedsDeliveryCheckoutAndProtectsOtherOwners() {
        long version = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        var cart = carts.addToCart(4L, add(5, 2));
        var tea = cart.items().stream().filter(item -> item.food().id() == 5).findFirst().orElseThrow();
        assertNotNull(tea.id());
        assertEquals("ACTIVE", tea.food().status());
        var update = new UpdateCartItemRequest(3);
        carts.updateCartItem(4L, tea.id(), update);
        assertEquals("CART_ITEM_NOT_FOUND", assertThrows(BusinessException.class,
                () -> carts.removeCartItem(5L, tea.id())).code());
        assertEquals("CART_ITEM_NOT_FOUND", assertThrows(BusinessException.class,
                () -> carts.updateCartItem(5L, tea.id(), update)).code());
        carts.removeCartItem(4L, 2L);
        assertTrue(db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class) > version);
        int before = stock(5);
        var order = service.create(CUSTOMER, new CreateOrderRequest(
                OrderType.DELIVERY, null, "Khách", "0900000004", "Địa chỉ", PaymentMethod.COD, null));
        assertEquals(3, order.items().stream().filter(item -> item.foodId() == 5).findFirst().orElseThrow().quantity());
        assertFalse(order.items().stream().anyMatch(item -> item.foodId() == 2));
        assertEquals(before - 3, stock(5));
        assertTrue(carts.getCart(4L).items().isEmpty());
        assertEquals(3, carts.getCart(5L).items().size());
    }

    @Test void cartShowsFoodThatStoppedSellingButCannotAddIt() {
        db.update("UPDATE foods SET status = 'INACTIVE' WHERE id = 2");
        var item = carts.getCart(4L).items().stream().filter(i -> i.food().id() == 2).findFirst().orElseThrow();
        assertEquals("INACTIVE", item.food().status());
        assertEquals("FOOD_UNAVAILABLE", assertThrows(BusinessException.class, () -> carts.addToCart(4L, add(2, 1))).code());
        assertEquals("FOOD_NOT_FOUND", assertThrows(BusinessException.class, () -> carts.addToCart(4L, add(999, 1))).code());
        assertEquals(1, db.queryForObject("SELECT quantity FROM cart_items WHERE cart_id = 1 AND food_id = 2", Integer.class));
    }

    @Test void cartVersionAndTimestampsComeFromMysql() {
        db.update("DELETE FROM carts WHERE user_id = 6");
        carts.addToCart(6L, add(5, 1));
        assertEquals(1L, db.queryForObject("SELECT version FROM carts WHERE user_id = 6", Long.class));
        var item = carts.getCart(6L).items().getFirst();
        var update = new UpdateCartItemRequest(4);
        carts.updateCartItem(6L, item.id(), update);
        carts.addToCart(6L, add(1, 1));
        carts.removeCartItem(6L, item.id());
        assertEquals(4L, db.queryForObject("SELECT version FROM carts WHERE user_id = 6", Long.class));
        // Cả created_at và updated_at đều do MySQL đặt, nên CHECK updated_at >= created_at luôn đúng.
        assertEquals(Boolean.TRUE, db.queryForObject("""
                SELECT updated_at >= created_at AND TIMESTAMPDIFF(SECOND, updated_at, NOW(6)) < 60
                FROM carts WHERE user_id = 6""", Boolean.class));
        assertEquals(Boolean.TRUE, db.queryForObject("""
                SELECT ci.updated_at >= ci.created_at AND TIMESTAMPDIFF(SECOND, ci.created_at, NOW(6)) < 60
                FROM cart_items ci JOIN carts c ON c.id = ci.cart_id WHERE c.user_id = 6""", Boolean.class));
    }

    @Test void quantityOverflowDoesNotChangeCartOrVersion() {
        db.update("UPDATE cart_items SET quantity = ? WHERE id = 1", Integer.MAX_VALUE);
        long version = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        var error = assertThrows(BusinessException.class, () -> carts.addToCart(4L, add(1, 1)));
        assertEquals(BusinessException.Type.INVALID, error.type());
        assertEquals(Integer.MAX_VALUE, db.queryForObject("SELECT quantity FROM cart_items WHERE id = 1", Integer.class));
        assertEquals(version, db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class));
    }

    @Test void concurrentFirstAdditionsCreateOneCartAndMergeQuantities() throws Exception {
        db.update("DELETE FROM carts WHERE user_id = 6");
        Callable<Boolean> add = () -> carts.addToCart(6L, add(5, 1)) != null;
        race(add, add);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM carts WHERE user_id = 6", Integer.class));
        var items = carts.getCart(6L).items();
        assertEquals(1, items.size());
        assertEquals(2, items.getFirst().quantity());
    }

    @Test void concurrentCustomersCannotOversellLastFood() throws Exception {
        db.update("DELETE FROM cart_items");
        carts.addToCart(4L, add(1, 1));
        carts.addToCart(5L, add(1, 1));
        db.update("UPDATE foods SET stock_quantity = 1 WHERE id = 1");
        assertExactlyOneSucceeds(race(() -> checkout(4), () -> checkout(5)));
        assertEquals(0, stock(1));
        assertEquals(9, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class));
    }

    @Test void additionsWaitForCheckoutAndRemainInTheNextCart() throws Exception {
        var clearing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var adding = new CountDownLatch(1);
        var added = new CountDownLatch(1);
        doAnswer(call -> {
            clearing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(cartTarget()).clearCart(anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var checkout = pool.submit(() -> service.create(CUSTOMER, dineIn()));
            try {
                assertTrue(clearing.await(10, TimeUnit.SECONDS));
                var addition = pool.submit(() -> {
                    adding.countDown();
                    var result = carts.addToCart(4L, add(5, 2));
                    added.countDown();
                    return result;
                });
                assertTrue(adding.await(5, TimeUnit.SECONDS));
                assertFalse(added.await(250, TimeUnit.MILLISECONDS));
                release.countDown();
                assertFalse(checkout.get(15, TimeUnit.SECONDS).items().stream().anyMatch(item -> item.foodId() == 5));
                var remaining = addition.get(15, TimeUnit.SECONDS).items();
                assertEquals(1, remaining.size());
                assertEquals(5L, remaining.getFirst().food().id());
                assertEquals(2, remaining.getFirst().quantity());
            } finally {
                release.countDown();
            }
        }
    }

    @Test void cartWriteAndCheckoutRollBackTogether() {
        int before = stock(5);
        long version = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            carts.addToCart(4L, add(5, 2));
            service.create(CUSTOMER, dineIn());
            throw new IllegalStateException("rollback cart and checkout");
        }));
        assertEquals(before, stock(5));
        assertEquals(version, db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class));
        assertEquals(3, carts.getCart(4L).items().size());
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    /** Mở cùng lúc hai tác vụ trên hai luồng và trả kết quả của chúng. */
    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return first.call(); });
            var b = pool.submit(() -> { gate.await(); return second.call(); });
            gate.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private static void assertExactlyOneSucceeds(List<Boolean> results) {
        assertEquals(1, results.stream().filter(ok -> ok).count(), "đúng một trong hai tác vụ thành công");
    }

    private boolean checkout(long userId) {
        try {
            service.create(userId, dineIn());
            return true;
        } catch (BusinessException error) {
            assertEquals("INSUFFICIENT_STOCK", error.code());
            return false;
        }
    }

    private static AddToCartRequest add(long foodId, int quantity) {
        return new AddToCartRequest(foodId, quantity);
    }

    private int stock(long id) { return db.queryForObject("SELECT stock_quantity FROM foods WHERE id = ?", Integer.class, id); }

    private long foodVersion(long id) { return db.queryForObject("SELECT version FROM foods WHERE id = ?", Long.class, id); }
}
