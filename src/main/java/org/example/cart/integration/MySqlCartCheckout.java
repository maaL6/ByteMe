package org.example.cart.integration;

import org.example.order.service.port.CartCheckout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Joins Order's transaction; Cart CRUD locks the same carts row before changing items. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class MySqlCartCheckout implements CartCheckout {
    private final JdbcTemplate db;

    public MySqlCartCheckout(JdbcTemplate db) { this.db = db; }

    @Override public CartSnapshot lockByUserId(long userId) {
        var ids = db.query("SELECT id FROM carts WHERE user_id = ? FOR UPDATE",
                (row, index) -> row.getLong("id"), userId);
        if (ids.isEmpty()) return null;
        long cartId = ids.getFirst();
        var items = db.query("SELECT food_id, quantity FROM cart_items WHERE cart_id = ? ORDER BY food_id",
                (row, index) -> new CartItem(row.getLong("food_id"), row.getInt("quantity")), cartId);
        return new CartSnapshot(cartId, items);
    }

    @Override public void clearItems(long cartId) {
        db.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
        db.update("UPDATE carts SET version = version + 1 WHERE id = ?", cartId);
    }
}
