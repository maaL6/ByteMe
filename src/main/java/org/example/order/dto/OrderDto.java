package org.example.order.dto;

import org.example.order.entity.Order;
import org.example.order.entity.OrderStatus;
import org.example.order.entity.OrderType;
import org.example.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDto(Long id, Long userId, OrderType orderType, OrderStatus status,
        String tableNumber, String recipientName, String shippingPhone, String shippingAddress,
        PaymentMethod paymentMethod, String note, Long confirmedBy, Instant confirmedAt,
        Instant cancelledAt, Instant completedAt, Instant createdAt, Instant updatedAt,
        List<OrderItemDto> items, BigDecimal totalAmount) {

    /** Tổng tiền tính từ snapshot các dòng đơn, không lưu riêng trên bảng orders. */
    public static OrderDto fromEntity(Order order) {
        List<OrderItemDto> items = order.getItems().stream().map(OrderItemDto::fromEntity).toList();
        BigDecimal total = items.stream().map(OrderItemDto::lineTotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new OrderDto(order.getId(), order.getUserId(), order.getOrderType(), order.getStatus(),
                order.getTableNumber(), order.getRecipientName(), order.getShippingPhone(), order.getShippingAddress(),
                order.getPaymentMethod(), order.getNote(), order.getConfirmedBy(), order.getConfirmedAt(),
                order.getCancelledAt(), order.getCompletedAt(), order.getCreatedAt(), order.getUpdatedAt(),
                items, total);
    }
}
