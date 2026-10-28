package org.example.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.order.domain.OrderType;
import org.example.order.domain.PaymentMethod;

// Items come from the customer's cart.
public record CreateOrderRequest(
        @NotNull(message = "orderType là bắt buộc")
        OrderType orderType,

        @Size(max = 20, message = "tableNumber tối đa 20 ký tự")
        String tableNumber,

        @Size(max = 150, message = "recipientName tối đa 150 ký tự")
        String recipientName,

        @Size(max = 20, message = "shippingPhone tối đa 20 ký tự")
        String shippingPhone,

        @Size(max = 500, message = "shippingAddress tối đa 500 ký tự")
        String shippingAddress,

        @NotNull(message = "paymentMethod là bắt buộc")
        PaymentMethod paymentMethod,

        @Size(max = 500, message = "note tối đa 500 ký tự")
        String note) {
}
