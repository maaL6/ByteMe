package org.example.order.integration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.example.order.service.OrderService;
import org.example.order.service.port.*;

@Configuration
public class OrderConfiguration {
    @Bean OrderService orderService(OrderRepository orders, CartCheckout carts, OrderFoodRepository foods,
            UnitOfWork transactions, OrderEvents events) {
        return new OrderService(orders, carts, foods, transactions, events, Clock.systemUTC());
    }
}
