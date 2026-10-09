package org.example.order.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.example.order.domain.*;
import org.example.order.service.port.OrderRepository;

@Repository
public class MySqlOrderRepository implements OrderRepository {
    private final NamedParameterJdbcTemplate db;

    public MySqlOrderRepository(NamedParameterJdbcTemplate db) { this.db = db; }

    @Override @Transactional(readOnly = true)
    public Optional<Order> findById(long id) {
        return db.query("SELECT * FROM orders WHERE id = :id", Map.of("id", id),
                (row, index) -> map(row)).stream().findFirst();
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lockById(long id) {
        return db.query("SELECT * FROM orders WHERE id = :id FOR UPDATE", Map.of("id", id),
                (row, index) -> map(row)).stream().findFirst();
    }

    @Override @Transactional(readOnly = true)
    public OrderPage findAll(Long userId, List<OrderStatus> statuses, OrderType type, int page, int size) {
        List<String> selected = (statuses.isEmpty() ? Arrays.asList(OrderStatus.values()) : statuses)
                .stream().map(Enum::name).toList();
        var parameters = new MapSqlParameterSource().addValue("userId", userId)
                .addValue("type", type == null ? null : type.name()).addValue("statuses", selected)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String filter = """
                FROM orders WHERE (:userId IS NULL OR user_id = :userId)
                AND (:type IS NULL OR order_type = :type) AND status IN (:statuses)
                """;
        long count = db.queryForObject("SELECT COUNT(*) " + filter, parameters, Long.class);
        var result = db.query("SELECT * " + filter + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                parameters, (row, index) -> map(row));
        return new OrderPage(result, page, size, count);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Order save(Order order) {
        var parameters = new MapSqlParameterSource().addValue("status", order.status().name())
                .addValue("confirmedBy", order.confirmedBy()).addValue("confirmedAt", utc(order.confirmedAt()))
                .addValue("cancelledAt", utc(order.cancelledAt())).addValue("completedAt", utc(order.completedAt()))
                .addValue("updatedAt", utc(order.updatedAt()));
        long id;
        if (order.id() == null) {
            parameters.addValue("userId", order.userId()).addValue("type", order.orderType().name())
                    .addValue("table", order.tableNumber()).addValue("recipient", order.recipientName())
                    .addValue("phone", order.shippingPhone()).addValue("address", order.shippingAddress())
                    .addValue("payment", order.paymentMethod().name()).addValue("note", order.note())
                    .addValue("createdAt", utc(order.createdAt()));
            var key = new GeneratedKeyHolder();
            db.update("""
                    INSERT INTO orders (user_id, order_type, status, table_number, recipient_name,
                        shipping_phone, shipping_address, payment_method, note, created_at, updated_at)
                    VALUES (:userId, :type, :status, :table, :recipient, :phone, :address, :payment,
                        :note, :createdAt, :updatedAt)
                    """, parameters, key, new String[]{"id"});
            id = key.getKey().longValue();
            for (var item : order.items()) {
                db.update("""
                        INSERT INTO order_items (order_id, food_id, food_name, unit_price, quantity, created_at)
                        VALUES (:orderId, :foodId, :name, :price, :quantity, :createdAt)
                        """, Map.of("orderId", id, "foodId", item.foodId(), "name", item.foodName(),
                        "price", item.unitPrice(), "quantity", item.quantity(), "createdAt", utc(order.createdAt())));
            }
        } else {
            id = order.id();
            parameters.addValue("id", id);
            int changed = db.update("""
                    UPDATE orders SET status = :status, confirmed_by = :confirmedBy, confirmed_at = :confirmedAt,
                        cancelled_at = :cancelledAt, completed_at = :completedAt, updated_at = :updatedAt,
                        version = version + 1 WHERE id = :id
                    """, parameters);
            if (changed != 1) throw new IllegalStateException("Không thể cập nhật đơn hàng.");
        }
        return findById(id).orElseThrow();
    }

    private Order map(ResultSet row) throws SQLException {
        long id = row.getLong("id");
        var items = db.query("SELECT food_id, food_name, unit_price, quantity FROM order_items WHERE order_id = :id ORDER BY id",
                Map.of("id", id), (item, index) -> new OrderItem(item.getLong("food_id"), item.getString("food_name"),
                        item.getBigDecimal("unit_price"), item.getInt("quantity")));
        return new Order(id, row.getLong("user_id"), OrderType.valueOf(row.getString("order_type")),
                OrderStatus.valueOf(row.getString("status")), row.getString("table_number"), row.getString("recipient_name"),
                row.getString("shipping_phone"), row.getString("shipping_address"),
                PaymentMethod.valueOf(row.getString("payment_method")), row.getString("note"),
                row.getObject("confirmed_by", Long.class), instant(row, "confirmed_at"), instant(row, "cancelled_at"),
                instant(row, "completed_at"), instant(row, "created_at"), instant(row, "updated_at"), items);
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
