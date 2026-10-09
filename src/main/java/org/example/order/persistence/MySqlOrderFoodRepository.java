package org.example.order.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.example.order.service.OrderFailure;
import org.example.order.service.port.OrderFoodRepository;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class MySqlOrderFoodRepository implements OrderFoodRepository {
    private final JdbcTemplate db;

    public MySqlOrderFoodRepository(JdbcTemplate db) { this.db = db; }

    @Override public Food lockById(long foodId) {
        return db.query("SELECT id, name, price, stock_quantity, status FROM foods WHERE id = ? FOR UPDATE",
                (row, index) -> new Food(row.getLong("id"), row.getString("name"), row.getBigDecimal("price"),
                        row.getInt("stock_quantity"), "ACTIVE".equals(row.getString("status"))), foodId)
                .stream().findFirst().orElseThrow(() -> new OrderFailure("FOOD_NOT_FOUND", "Không tìm thấy món ăn."));
    }

    @Override public void decreaseStock(long foodId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity phải dương");
        int changed = db.update("""
                UPDATE foods SET stock_quantity = stock_quantity - ?, version = version + 1
                WHERE id = ? AND stock_quantity >= ? AND status = 'ACTIVE'
                """, quantity, foodId, quantity);
        if (changed != 1) throw new OrderFailure("INSUFFICIENT_STOCK", "Không thể trừ tồn kho món ăn.");
    }

    @Override public void restoreStock(long foodId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity phải dương");
        int changed = db.update("UPDATE foods SET stock_quantity = stock_quantity + ?, version = version + 1 WHERE id = ?",
                quantity, foodId);
        if (changed != 1) throw new OrderFailure("FOOD_NOT_FOUND", "Không tìm thấy món ăn để hoàn kho.");
    }
}
