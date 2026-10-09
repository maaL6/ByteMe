package org.example.order.integration;

import org.example.order.service.OrderFailure;
import org.example.order.service.port.CartCheckout;

/** Replace this adapter when Cart Service is available; never fabricate a cart. */
public final class CartCheckoutPlaceholder implements CartCheckout {
    @Override public CartSnapshot lockByUserId(long userId) { throw unavailable(); }
    @Override public void clearItems(long cartId) { throw unavailable(); }

    private static OrderFailure unavailable() {
        return new OrderFailure("CART_NOT_IMPLEMENTED", "Cart Service chưa được triển khai; chưa thể tạo đơn hàng.");
    }
}
