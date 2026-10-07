package org.example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DatabaseDesignTests {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("byteme_database_design")
            .withInitScript("db/schema.sql");

    private static final Map<String, Long> COUNTS = Map.of(
            "users", 8L, "categories", 6L, "foods", 18L, "carts", 5L,
            "cart_items", 6L, "orders", 8L, "order_items", 16L);
    private static final Map<String, String> SNAPSHOTS = Map.of(
            "foods", "SELECT id, category_id, price, stock_quantity, status, version FROM foods ORDER BY id",
            "cart_items", "SELECT * FROM cart_items ORDER BY id",
            "order_items", "SELECT * FROM order_items ORDER BY id");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate db;
    @BeforeAll
    static void loadSampleData() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        db = new JdbcTemplate(dataSource);
        runScript("db/seed.sql");
    }

    @Test
    void sampleDataHasExpectedCountsAndHistoricalTotals() {
        Map<Long, BigDecimal> expectedTotals = Map.of(
                1001L, new BigDecimal("180000.00"), 1002L, new BigDecimal("77000.00"),
                1003L, new BigDecimal("126000.00"), 1004L, new BigDecimal("130000.00"),
                1005L, new BigDecimal("152000.00"), 1006L, new BigDecimal("90000.00"),
                1007L, new BigDecimal("110000.00"), 1008L, new BigDecimal("125000.00"));
        expectedTotals.forEach((id, amount) -> assertEquals(0, amount.compareTo(total(id))));
        COUNTS.forEach((table, count) -> assertEquals(count,
                db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), table));
        assertEquals(Set.of("CUSTOMER", "EMPLOYEE", "ADMIN"), values("SELECT DISTINCT role FROM users"));
        assertEquals(Set.of("PENDING", "CONFIRMED", "PROCESSING", "SHIPPING", "COMPLETED", "CANCELLED"),
                values("SELECT DISTINCT status FROM orders"));
        assertEquals(Set.of("1002:CONFIRMED", "1003:PROCESSING", "1005:COMPLETED", "1006:CANCELLED"),
                values("SELECT CONCAT(id, ':', status) FROM orders WHERE order_type = 'DINE_IN'"));
        assertEquals(Set.of("1001:PENDING", "1004:SHIPPING", "1007:COMPLETED", "1008:PENDING"),
                values("SELECT CONCAT(id, ':', status) FROM orders WHERE order_type = 'DELIVERY'"));
        assertEquals(18L, db.queryForObject("SELECT COUNT(DISTINCT normalized_name) FROM foods", Long.class));
        assertEquals(0L, db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                AND ((table_name = 'orders' AND column_name = 'total_amount')
                     OR (table_name = 'foods' AND column_name = 'restaurant_id'))
                """, Long.class));
    }

    @Test
    void demoPasswordsAreBcryptHashes() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        for (String hash : db.queryForList("SELECT password_hash FROM users", String.class)) {
            assertEquals(60, hash.length());
            assertTrue(encoder.matches("ByteMeDemo!2026", hash));
            assertFalse(encoder.matches("wrong-password", hash));
        }
    }

    @Test
    void namesAndAccountIdentifiersCannotBeDuplicated() {
        rollback(jdbc -> {
            reject(jdbc, "UPDATE users SET email = UPPER('admin@byteme.example') WHERE id = 2");
            reject(jdbc, "UPDATE users SET phone = '0900000001' WHERE id = 2");
            reject(jdbc, "INSERT INTO categories (name) VALUES ('  CƠM  ')");
            reject(jdbc, "UPDATE categories SET name = '  CƠM  ' WHERE id = 3");
            reject(jdbc, """
                    INSERT INTO foods (category_id, name, price)
                    SELECT 2, CONCAT('  ', UPPER(name), '  '), price FROM foods WHERE id = 1
                    """);
            jdbc.update("INSERT INTO categories (name) VALUES ('  Danh mục mới  ')");
            assertEquals("danh mục mới", jdbc.queryForObject(
                    "SELECT normalized_name FROM categories WHERE id = LAST_INSERT_ID()", String.class));
            jdbc.update("INSERT INTO categories (name) VALUES ('Com')");
        });
    }

    @Test
    void cartLinesAndReferencesStayConsistent() {
        rollback(jdbc -> {
            reject(jdbc, "INSERT INTO carts (user_id) VALUES (4)");
            reject(jdbc, "INSERT INTO cart_items (cart_id, food_id, quantity) VALUES (1, 1, 1)");
            reject(jdbc, """
                    INSERT INTO order_items (order_id, food_id, food_name, unit_price, quantity)
                    SELECT order_id, food_id, food_name, unit_price, quantity FROM order_items WHERE id = 1
                    """);
            reject(jdbc, "UPDATE foods SET category_id = 9223372036854775807 WHERE id = 1");
            reject(jdbc, "UPDATE cart_items SET food_id = 9223372036854775807 WHERE id = 1");
            reject(jdbc, "UPDATE orders SET user_id = 9223372036854775807 WHERE id = 1001");
            reject(jdbc, "DELETE FROM foods WHERE id = 1");
            reject(jdbc, "DELETE FROM orders WHERE id = 1001");
            jdbc.update("DELETE FROM carts WHERE id = 1");
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = 1", Long.class));
            assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Long.class));
            assertEquals(16L, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class));
        });
    }

    @Test
    void invalidAmountsQuantitiesAndEnumValuesAreRejected() {
        rollback(jdbc -> {
            for (String sql : List.of(
                    "UPDATE foods SET price = 0 WHERE id = 1",
                    "UPDATE foods SET stock_quantity = -1 WHERE id = 1",
                    "UPDATE foods SET version = -1 WHERE id = 1",
                    "UPDATE carts SET version = -1 WHERE id = 1",
                    "UPDATE orders SET version = -1 WHERE id = 1001",
                    "UPDATE cart_items SET quantity = 0 WHERE id = 1",
                    "UPDATE order_items SET quantity = 0 WHERE id = 1",
                    "UPDATE order_items SET unit_price = 0 WHERE id = 1",
                    "UPDATE users SET role = 'customer' WHERE id = 4",
                    "UPDATE users SET role = 'MANAGER' WHERE id = 4",
                    "UPDATE users SET status = 'UNKNOWN' WHERE id = 4",
                    "UPDATE foods SET status = 'active' WHERE id = 1",
                    "UPDATE foods SET status = 'DELETED' WHERE id = 1",
                    "UPDATE orders SET status = 'DELIVERED' WHERE id = 1001",
                    "UPDATE orders SET order_type = 'delivery' WHERE id = 1001",
                    "UPDATE orders SET order_type = 'TAKEAWAY' WHERE id = 1001",
                    "UPDATE orders SET order_type = NULL WHERE id = 1001",
                    "UPDATE orders SET payment_method = 'cod' WHERE id = 1001",
                    "UPDATE orders SET payment_method = 'CREDIT_CARD' WHERE id = 1001")) {
                reject(jdbc, sql);
            }
        });
    }

    @Test
    void fulfillmentFieldsMustMatchTheOrderType() {
        rollback(jdbc -> {
            for (String column : List.of("recipient_name", "shipping_phone", "shipping_address")) {
                for (Object value : new Object[] {null, "", "   "}) {
                    reject(jdbc, "UPDATE orders SET " + column + " = ? WHERE id = 1001", value);
                }
                for (String value : List.of("x", "", "   ")) {
                    reject(jdbc, "UPDATE orders SET " + column + " = ? WHERE id = 1002", value);
                }
            }
            for (Object table : new Object[] {null, "", "   "}) {
                reject(jdbc, "UPDATE orders SET table_number = ? WHERE id = 1002", table);
            }
            for (String table : List.of("B01", "", "   ")) {
                reject(jdbc, "UPDATE orders SET table_number = ? WHERE id = 1001", table);
            }
            reject(jdbc, "UPDATE orders SET payment_method = 'CASH' WHERE id = 1001");
            reject(jdbc, "UPDATE orders SET payment_method = 'COD' WHERE id = 1002");
            reject(jdbc, "UPDATE orders SET status = 'SHIPPING' WHERE id = 1002");
        });
    }

    @Test
    void orderTypeIsExplicitAndSupportedPaymentsAreAccepted() {
        rollback(jdbc -> {
            reject(jdbc, """
                    INSERT INTO orders (user_id, recipient_name, shipping_phone, shipping_address, payment_method)
                    VALUES (4, 'Demo recipient', '0900000004', 'Demo address', 'COD')
                    """);
            for (String method : List.of("CASH", "BANK_TRANSFER")) {
                jdbc.update("""
                        INSERT INTO orders (user_id, order_type, table_number, payment_method)
                        VALUES (4, 'DINE_IN', 'B01', ?)
                        """, method);
            }
            for (String method : List.of("COD", "BANK_TRANSFER")) {
                jdbc.update("""
                        INSERT INTO orders (user_id, order_type, recipient_name, shipping_phone, shipping_address, payment_method)
                        VALUES (4, 'DELIVERY', 'Demo recipient', '0900000004', 'Demo address', ?)
                        """, method);
            }
            long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            assertEquals(0, total(orderId).signum());
            jdbc.update("""
                    INSERT INTO order_items (order_id, food_id, food_name, unit_price, quantity)
                    VALUES (?, 1, 'Tên lúc đặt', 12345.50, 2)
                    """, orderId);
            assertEquals(0, new BigDecimal("24691.00").compareTo(total(orderId)));
        });
    }

    @Test
    void statesRequireConsistentConfirmationAndTerminalTimes() {
        rollback(jdbc -> {
            reject(jdbc, "UPDATE orders SET confirmed_by = 2, confirmed_at = '2026-09-30 03:01:00' WHERE id = 1001");
            reject(jdbc, "UPDATE orders SET cancelled_at = '2026-09-30 03:01:00' WHERE id = 1001");
            reject(jdbc, "UPDATE orders SET completed_at = '2026-09-30 03:01:00' WHERE id = 1001");
            for (String status : List.of("CONFIRMED", "PROCESSING", "SHIPPING", "COMPLETED", "CANCELLED")) {
                reject(jdbc, "UPDATE orders SET status = ? WHERE id = 1001", status);
            }
            for (long id : new long[] {1002, 1003, 1004, 1005, 1007}) {
                reject(jdbc, "UPDATE orders SET confirmed_by = NULL, confirmed_at = NULL WHERE id = ?", id);
            }
            reject(jdbc, "UPDATE orders SET completed_at = NULL WHERE id = 1005");
            reject(jdbc, "UPDATE orders SET cancelled_at = NULL WHERE id = 1006");
            reject(jdbc, "UPDATE orders SET status = 'PROCESSING' WHERE id = 1005");
            reject(jdbc, "UPDATE orders SET status = 'PENDING' WHERE id = 1006");
            reject(jdbc, "UPDATE orders SET completed_at = '2026-09-26 02:04:00' WHERE id = 1005");
            reject(jdbc, "UPDATE orders SET confirmed_by = 2, confirmed_at = '2026-09-29 07:11:00' WHERE id = 1006");
            jdbc.update("UPDATE orders SET status = 'CANCELLED', cancelled_at = '2026-09-30 03:01:00' WHERE id = 1002");
            assertEquals(2L, jdbc.queryForObject("SELECT confirmed_by FROM orders WHERE id = 1002", Long.class));
            jdbc.update("UPDATE orders SET status = 'COMPLETED', completed_at = '2026-09-30 02:31:00' WHERE id = 1003");
        });
    }

    @Test
    void totalsFollowItemChangesWithoutUpdatingTheHeader() {
        rollback(jdbc -> {
            BigDecimal oldTotal = total(1001);
            jdbc.update("UPDATE order_items SET quantity = quantity + 1 WHERE id = 1");
            assertEquals(0, oldTotal.add(new BigDecimal("65000.00")).compareTo(total(1001)));
            jdbc.update("DELETE FROM order_items WHERE id = 1");
            assertEquals(0, oldTotal.subtract(new BigDecimal("130000.00")).compareTo(total(1001)));
        });
    }

    @Test
    void changingTheMenuPreservesOrderSnapshotsAndTotals() {
        rollback(jdbc -> {
            List<Map<String, Object>> items = jdbc.queryForList("SELECT * FROM order_items ORDER BY id");
            BigDecimal oldTotal = total(1001);
            jdbc.update("UPDATE foods SET name = 'Tên món mới', price = price + 1000 WHERE id = 1");
            assertEquals(items, jdbc.queryForList("SELECT * FROM order_items ORDER BY id"));
            assertEquals(oldTotal, total(1001));
        });
    }

    @Test
    void schemaCanBeRerunWithoutChangingData() {
        var before = snapshots();
        runScript("db/schema.sql");
        assertEquals(before, snapshots());
        assertEquals(new BigDecimal("180000.00"), total(1001));
    }

    @Test
    void sampleDataCannotBeLoadedTwice() {
        var before = snapshots();
        assertThrows(DataAccessException.class, () -> runScript("db/seed.sql"));
        assertEquals(before, snapshots());
        COUNTS.forEach((table, count) -> assertEquals(count,
                db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), table));
    }

    private static void runScript(String path) {
        new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource);
    }

    private static Map<String, List<Map<String, Object>>> snapshots() {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        SNAPSHOTS.forEach((table, sql) -> result.put(table, db.queryForList(sql)));
        return result;
    }

    private static Set<String> values(String sql) {
        return Set.copyOf(db.queryForList(sql, String.class));
    }

    private static BigDecimal total(long orderId) {
        return db.queryForObject("SELECT total_amount FROM order_totals WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private static void rollback(Consumer<JdbcTemplate> action) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            action.accept(db);
        });
    }

    private static void reject(JdbcTemplate jdbc, String sql, Object... arguments) {
        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.update(sql, arguments), sql);
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertTrue((cause.getSQLState() != null && cause.getSQLState().startsWith("23"))
                || Set.of(3819, 1265, 1364, 1366).contains(cause.getErrorCode()), failure.getMessage());
    }
}
