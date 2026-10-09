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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @MockitoBean CartCheckout carts;

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

    @BeforeEach void seedAndTestOnlyCartAdapter() {
        // This database belongs solely to this ephemeral test container.
        for (String table : List.of("order_items", "orders", "cart_items", "carts", "foods", "categories", "users"))
            db.update("DELETE FROM " + table);
        new ResourceDatabasePopulator(new ClassPathResource("db/seed.sql"))
                .execute(Objects.requireNonNull(db.getDataSource()));
        recorder.received.clear();
        recorder.committedRows.clear();
        // Only tests implement Cart; the application's default remains unavailable.
        when(carts.lockByUserId(anyLong())).thenAnswer(call -> {
            long userId = call.getArgument(0);
            long cartId = db.queryForObject("SELECT id FROM carts WHERE user_id = ? FOR UPDATE", Long.class, userId);
            var items = db.query("SELECT food_id, quantity FROM cart_items WHERE cart_id = ? ORDER BY food_id",
                    (row, index) -> new CartCheckout.CartItem(row.getLong("food_id"), row.getInt("quantity")), cartId);
            return new CartCheckout.CartSnapshot(cartId, items);
        });
        doAnswer(call -> {
            long cartId = call.getArgument(0);
            db.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
            db.update("UPDATE carts SET version = version + 1 WHERE id = ?", cartId);
            return null;
        }).when(carts).clearItems(anyLong());
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
        doAnswer(call -> {
            db.update("DELETE FROM cart_items WHERE cart_id = ?", (Long) call.getArgument(0));
            throw new IllegalStateException("Synthetic cart failure");
        }).when(carts).clearItems(anyLong());
        assertThrows(IllegalStateException.class, () -> service.create(OrderServiceTest.CUSTOMER, OrderServiceTest.dineIn()));
        assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(16, db.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(originalStock, stock(1));
        assertEquals(originalCartItems, db.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Integer.class));
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

    private int stock(long id) { return db.queryForObject("SELECT stock_quantity FROM foods WHERE id = ?", Integer.class, id); }
}
