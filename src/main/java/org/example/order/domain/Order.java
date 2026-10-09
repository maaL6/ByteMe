package org.example.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Order(Long id, long userId, OrderType orderType, OrderStatus status,
        String tableNumber, String recipientName, String shippingPhone, String shippingAddress,
        PaymentMethod paymentMethod, String note, Long confirmedBy, Instant confirmedAt,
        Instant cancelledAt, Instant completedAt, Instant createdAt, Instant updatedAt,
        List<OrderItem> items) {
    public Order { items = List.copyOf(items); }

    public BigDecimal totalAmount() {
        BigDecimal total = new BigDecimal("0.00");
        for (OrderItem item : items) total = total.add(item.lineTotal());
        return total;
    }

    public BigDecimal getTotalAmount() { return totalAmount(); }

    public Order transitionTo(OrderStatus next, long employeeId, Instant now) {
        Long confirmer = confirmedBy;
        Instant confirmationTime = confirmedAt;
        Instant cancellationTime = cancelledAt;
        Instant completionTime = completedAt;
        if (next == OrderStatus.CONFIRMED) {
            confirmer = employeeId;
            confirmationTime = now;
        }
        if (next == OrderStatus.CANCELLED) cancellationTime = now;
        if (next == OrderStatus.COMPLETED) completionTime = now;
        return new Order(id, userId, orderType, next, tableNumber, recipientName, shippingPhone,
                shippingAddress, paymentMethod, note, confirmer, confirmationTime,
                cancellationTime, completionTime, createdAt, now, items);
    }
}
