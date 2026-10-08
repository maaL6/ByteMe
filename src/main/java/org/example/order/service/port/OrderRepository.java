package org.example.order.service.port;

import java.util.List;
import java.util.Optional;
import org.example.order.domain.Order;
import org.example.order.domain.OrderStatus;
import org.example.order.domain.OrderType;

public interface OrderRepository {
    record OrderPage(List<Order> content, int page, int size, long totalElements) {
        public OrderPage { content = List.copyOf(content); }
    }

    Optional<Order> findById(long id);
    Optional<Order> lockById(long id);
    OrderPage findAll(Long userId, List<OrderStatus> statuses, OrderType type, int page, int size);
    Order save(Order order);
}
