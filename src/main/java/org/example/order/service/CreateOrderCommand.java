package org.example.order.service;

import org.example.order.domain.OrderType;
import org.example.order.domain.PaymentMethod;

public record CreateOrderCommand(OrderType orderType, String tableNumber, String recipientName,
        String shippingPhone, String shippingAddress, PaymentMethod paymentMethod, String note) {}
