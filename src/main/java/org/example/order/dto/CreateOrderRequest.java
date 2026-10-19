package org.example.order.dto;

import org.example.order.entity.OrderType;
import org.example.order.entity.PaymentMethod;

public record CreateOrderRequest(OrderType orderType, String tableNumber,
        String recipientName, String shippingPhone, String shippingAddress,
        PaymentMethod paymentMethod, String note) {
}
