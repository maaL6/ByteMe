package org.example.order.dto;

import java.util.List;
import org.example.order.entity.Order;

public record OrderPage(List<Order> content, int page, int size, long totalElements) {}
