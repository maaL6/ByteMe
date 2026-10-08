package org.example.order.service.port;

import java.util.List;

/** Cart integration contract. Both operations must join the checkout transaction.
 * Cart mutations must lock the same cart row until commit/rollback. */
public interface CartCheckout {
    record CartItem(long foodId, int quantity) {}
    record CartSnapshot(long cartId, List<CartItem> items) {
        public CartSnapshot { items = List.copyOf(items); }
    }

    CartSnapshot lockByUserId(long userId);
    void clearItems(long cartId);
}
