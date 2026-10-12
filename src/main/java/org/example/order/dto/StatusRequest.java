package org.example.order.dto;

import org.example.order.entity.OrderStatus;

public record StatusRequest(OrderStatus status) {}
