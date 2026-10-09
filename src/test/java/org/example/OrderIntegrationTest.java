package org.example;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.web.server.ResponseStatusException;
import org.example.cart.integration.MySqlCartCheckout;
import org.example.cart.service.CartService;
import org.example.cart.dto.AddToCartRequest;
import org.example.cart.dto.UpdateCartItemRequest;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.example.order.domain.*;
import org.example.order.service.OrderFailure;
import org.example.order.service.OrderService;
import org.example.order.service.port.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(OrderIntegrationTest.EventConfiguration.class)
class OrderIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_integration").withInitScript("db/schema.sql");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate db;
    @Autowired OrderService service;
    @Autowired UnitOfWork transactions;
    @Autowired OrderEvents events;
    @Autowired EventRecorder recorder;
    @MockitoSpyBean MySqlCartCheckout carts;
    @Autowired CartService cartService;

    @TestConfiguration static class EventConfiguration {
        @Bean EventRecorder eventRecorder(JdbcTemplate db) { return new EventRecorder(db); }
    }

    static class EventRecorder {
        final List<OrderEvents.OrderCreated> received = new CopyOnWriteArrayList<>();
        final List<Integer> committedRows = new CopyOnWriteArrayList<>();
        private final JdbcTemplate db;

        EventRecorder(JdbcTemplate db) { this.db = db; }

        @TransactionalEventListener
        public void created(OrderEvents.OrderCreated event) {
            committedRows.add(db.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ?", Integer.class, event.orderId()));
            received.add(event);
        }
    }

    @BeforeEach void seed() {
        // This database belongs solely to this ephemeral test container.
        for (String table : List.of("order_items", "orders", "cart_items", "carts", "foods", "categories", "users"))
            db.update("DELETE FROM " + table);
        new ResourceDatabasePopulator(new ClassPathResource("db/seed.sql"))
                .execute(Objects.requireNonNull(db.getDataSource()));
        recorder.received.clear();
        recorder.committedRows.clear();
    }

    @Test void checkoutPersistsSnapshotsUpdatesStockAndEmitsAfterCommit() {
        int originalStock = stock(1);
        int originalCartVersion = db.queryForObject("SELECT version FROM carts WHERE id = 1", Integer.class);
        var order = service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn());
        assertEquals(OrderStatus.PENDING, order.status());
        assertFalse(order.items().isEmpty());
        int quantity = order.items().stream().filter(item -> item.foodId() == 1).findFirst().orElseThrow().quantity();
        assertEquals(originalStock - quantity, stock(1));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class));
        assertEquals(originalCartVersion + 1, db.queryForObject("SELECT version FROM carts WHERE id = 1", Integer.class));
        assertEquals(0, order.totalAmount().compareTo(db.queryForObject(
                "SELECT total_amount FROM order_totals WHERE order_id = ?", BigDecimal.class, order.id())));
        assertEquals(List.of(new OrderEvents.OrderCreated(order.id(), 4, 1)), recorder.received);
        assertEquals(List.of(1), recorder.committedRows);
        db.update("UPDATE foods SET name = 'Tên mới sau checkout', price = price + 1000 WHERE id = 1");
        var read = service.get(OrderServiceTest.CUSTOMER, order.id());
        assertEquals(order.items(), read.items());
        assertEquals(order.totalAmount(), read.totalAmount());
    }

    @Test void cartClearFailureRollsBackHeaderItemsStockAndCart() {
        int originalStock = stock(1);
        int originalCartItems = db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class);
        long originalVersion = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("Synthetic cart failure");
        }).when(AopTestUtils.<MySqlCartCheckout>getUltimateTargetObject(carts)).clearItems(anyLong());
        assertThrows(IllegalStateException.class, () -> service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn()));
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(16, db.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(originalStock, stock(1));
        assertEquals(originalCartItems, db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class));
        assertEquals(originalVersion, db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class));
        assertTrue(recorder.received.isEmpty());
    }

    @Test void afterCommitListenerIgnoresRollbackAndWaitsForCommit() {
        var event = new OrderEvents.OrderCreated(1001, 4, 1);
        assertThrows(IllegalStateException.class, () -> transactions.execute(() -> {
            events.publish(event);
            assertTrue(recorder.received.isEmpty());
            throw new IllegalStateException("rollback");
        }));
        assertTrue(recorder.received.isEmpty());
        transactions.execute(() -> {
            events.publish(event);
            assertTrue(recorder.received.isEmpty());
            return null;
        });
        assertEquals(List.of(event), recorder.received);
    }

    @Test void customerScopeEmployeeFiltersAndBothServingFlowsUseRealMysql() {
        var own = service.list(OrderServiceTest.CUSTOMER, null, null, 0, 20);
        assertTrue(own.content().stream().allMatch(order -> order.userId() == 4));
        assertEquals(db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = 4", Long.class), own.totalElements());
        var active = service.list(OrderServiceTest.EMPLOYEE, null, null, 0, 20);
        assertEquals(5, active.totalElements());
        assertTrue(service.list(OrderServiceTest.EMPLOYEE, OrderStatus.SHIPPING, OrderType.DINE_IN, 0, 20).content().isEmpty());
        var page = service.list(OrderServiceTest.CUSTOMER, null, null, 0, 1);
        assertEquals(1, page.content().size());
        assertEquals(own.totalElements(), page.totalElements());
        assertEquals(OrderStatus.PROCESSING, service.updateStatus(OrderServiceTest.EMPLOYEE, 1002, OrderStatus.PROCESSING).status());
        OrderServiceTest.assertCode("INVALID_ORDER_TRANSITION",
                () -> service.updateStatus(OrderServiceTest.EMPLOYEE, 1002, OrderStatus.SHIPPING));
        assertEquals(OrderStatus.COMPLETED, service.updateStatus(OrderServiceTest.EMPLOYEE, 1002, OrderStatus.COMPLETED).status());
        var confirmed = service.confirm(OrderServiceTest.EMPLOYEE, 1008);
        assertEquals(2L, confirmed.confirmedBy());
        assertNotNull(confirmed.confirmedAt());
        service.updateStatus(OrderServiceTest.EMPLOYEE, 1008, OrderStatus.PROCESSING);
        service.updateStatus(OrderServiceTest.EMPLOYEE, 1008, OrderStatus.SHIPPING);
        assertNotNull(service.updateStatus(OrderServiceTest.EMPLOYEE, 1008, OrderStatus.COMPLETED).completedAt());
    }

    @Test void employeeCancellationKeepsConfirmationAndRollsBackIfSavingFails() {
        int originalStock = stock(13);
        var cancelled = service.updateStatus(OrderServiceTest.EMPLOYEE, 1003, OrderStatus.CANCELLED);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.confirmedBy());
        assertNotNull(cancelled.confirmedAt());
        assertNotNull(cancelled.cancelledAt());
        assertEquals(originalStock + 2, stock(13));
        // A failed write after restoration must roll back both stock and status.
        int before = stock(1);
        assertThrows(RuntimeException.class, () -> transactions.execute(() -> {
            service.cancel(OrderServiceTest.CUSTOMER, 1001);
            throw new IllegalStateException("Synthetic failure after save");
        }));
        assertEquals(before, stock(1));
        assertEquals(OrderStatus.PENDING, service.get(OrderServiceTest.CUSTOMER, 1001).status());
    }

    @Test void concurrentCancellationRestoresStockExactlyOnce() throws Exception {
        int originalStock = stock(1);
        var gate = new CountDownLatch(1);
        Callable<Boolean> cancel = () -> {
            gate.await();
            try { service.cancel(OrderServiceTest.CUSTOMER, 1001); return true; }
            catch (OrderFailure error) { assertEquals("INVALID_ORDER_TRANSITION", error.code()); return false; }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(cancel);
            var second = pool.submit(cancel);
            gate.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(originalStock + 2, stock(1));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = 1001", Integer.class));
    }

    @Test void concurrentCheckoutCreatesOnlyOneOrderFromSameCart() throws Exception {
        int originalStock = stock(1);
        var gate = new CountDownLatch(1);
        Callable<Boolean> create = () -> {
            gate.await();
            try { service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn()); return true; }
            catch (OrderFailure error) { assertEquals("EMPTY_CART", error.code()); return false; }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(create);
            var second = pool.submit(create);
            gate.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(9, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(originalStock - 2, stock(1));
        assertEquals(1, recorder.received.size());
    }

    @Test void cartAdapterRequiresCheckoutTransactionAndMissingOrEmptyCartsDoNotCreateOrders() {
        assertThrows(IllegalTransactionStateException.class, () -> carts.lockByUserId(4));
        assertThrows(IllegalTransactionStateException.class, () -> carts.clearItems(1));
        db.update("DELETE FROM carts WHERE user_id = 6");
        OrderServiceTest.assertCode("CART_NOT_FOUND", () -> service.create(
                new org.example.shared.api.AuthenticatedUser("6", org.example.shared.api.Role.CUSTOMER), OrderServiceTest.dineIn()));
        db.update("DELETE FROM cart_items WHERE cart_id = 1");
        OrderServiceTest.assertCode("EMPTY_CART", () -> service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn()));
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test void jpaCartCrudFeedsDeliveryCheckoutAndProtectsOtherOwners() {
        long version = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        var cart = cartService.addToCart(4L, add(5, 2));
        var tea = cart.getItems().stream().filter(item -> item.getFood().getId() == 5).findFirst().orElseThrow();
        assertNotNull(tea.getId());
        var update = new UpdateCartItemRequest();
        update.setQuantity(3);
        cartService.updateCartItem(4L, tea.getId(), update);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> cartService.removeCartItem(5L, tea.getId())).getStatusCode().value());
        cartService.removeCartItem(4L, 2L);
        assertTrue(db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class) > version);
        int before = stock(5);
        var order = service.create(OrderServiceTest.CUSTOMER, new org.example.order.service.CreateOrderCommand(
                OrderType.DELIVERY, null, "Khách", "0900000004", "Địa chỉ", PaymentMethod.COD, null));
        assertEquals(3, order.items().stream().filter(item -> item.foodId() == 5).findFirst().orElseThrow().quantity());
        assertFalse(order.items().stream().anyMatch(item -> item.foodId() == 2));
        assertEquals(before - 3, stock(5));
        assertTrue(cartService.getCart(4L).getItems().isEmpty());
        assertEquals(3, cartService.getCart(5L).getItems().size());
    }

    @Test void quantityOverflowDoesNotChangeCartOrVersion() {
        db.update("UPDATE cart_items SET quantity = ? WHERE id = 1", Integer.MAX_VALUE);
        long version = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> cartService.addToCart(4L, add(1, 1))).getStatusCode().value());
        assertEquals(Integer.MAX_VALUE, db.queryForObject("SELECT quantity FROM cart_items WHERE id = 1", Integer.class));
        assertEquals(version, db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class));
    }

    @Test void concurrentFirstAdditionsCreateOneCartAndMergeQuantities() throws Exception {
        db.update("DELETE FROM carts WHERE user_id = 6");
        var gate = new CountDownLatch(1);
        Callable<Void> add = () -> { gate.await(); cartService.addToCart(6L, add(5, 1)); return null; };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(add);
            var second = pool.submit(add);
            gate.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM carts WHERE user_id = 6", Integer.class));
        var items = cartService.getCart(6L).getItems();
        assertEquals(1, items.size());
        assertEquals(2, items.getFirst().getQuantity());
    }

    @Test void concurrentCustomersCannotOversellLastFood() throws Exception {
        db.update("DELETE FROM cart_items");
        cartService.addToCart(4L, add(1, 1));
        cartService.addToCart(5L, add(1, 1));
        db.update("UPDATE foods SET stock_quantity = 1 WHERE id = 1");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> checkoutAfter(gate, 4));
            var second = pool.submit(() -> checkoutAfter(gate, 5));
            gate.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
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
        }).when(AopTestUtils.<MySqlCartCheckout>getUltimateTargetObject(carts)).clearItems(anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var checkout = pool.submit(() -> service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn()));
            try {
                assertTrue(clearing.await(10, TimeUnit.SECONDS));
                var addition = pool.submit(() -> {
                    adding.countDown();
                    var result = cartService.addToCart(4L, add(5, 2));
                    added.countDown();
                    return result;
                });
                assertTrue(adding.await(5, TimeUnit.SECONDS));
                assertFalse(added.await(250, TimeUnit.MILLISECONDS));
                release.countDown();
                assertFalse(checkout.get(15, TimeUnit.SECONDS).items().stream().anyMatch(item -> item.foodId() == 5));
                var remaining = addition.get(15, TimeUnit.SECONDS).getItems();
                assertEquals(1, remaining.size());
                assertEquals(5L, remaining.getFirst().getFood().getId());
                assertEquals(2, remaining.getFirst().getQuantity());
            } finally {
                release.countDown();
            }
        }
    }

    private boolean checkoutAfter(CountDownLatch gate, long userId) throws InterruptedException {
        gate.await();
        try {
            service.create(new org.example.shared.api.AuthenticatedUser(Long.toString(userId), org.example.shared.api.Role.CUSTOMER),
                    OrderServiceTest.dineIn());
            return true;
        } catch (OrderFailure error) {
            assertEquals("INSUFFICIENT_STOCK", error.code());
            return false;
        }
    }

    @Test void jpaCartWriteAndJdbcCheckoutRollBackTogether() {
        int before = stock(5);
        long version = db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class);
        assertThrows(IllegalStateException.class, () -> transactions.execute(() -> {
            cartService.addToCart(4L, add(5, 2));
            service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn());
            throw new IllegalStateException("rollback JPA and JDBC");
        }));
        assertEquals(before, stock(5));
        assertEquals(version, db.queryForObject("SELECT version FROM carts WHERE id = 1", Long.class));
        assertEquals(3, cartService.getCart(4L).getItems().size());
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertTrue(recorder.received.isEmpty());
    }

    private static AddToCartRequest add(long foodId, int quantity) {
        var request = new AddToCartRequest();
        request.setFoodId(foodId);
        request.setQuantity(quantity);
        return request;
    }

    private int stock(long id) { return db.queryForObject("SELECT stock_quantity FROM foods WHERE id = ?", Integer.class, id); }
}
