package org.example.order.service.port;

public interface OrderEvents {
    record OrderCreated(long orderId, long userId, long cartId) {}

    /** Publish inside the transaction; consumers must run only after a successful commit. */
    void publish(OrderCreated event);
}
