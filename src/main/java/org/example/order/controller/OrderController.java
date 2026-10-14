package org.example.order.controller;

import java.net.URI;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.example.order.dto.CreateOrderCommand;
import org.example.order.dto.StatusRequest;
import org.example.order.entity.Order;
import org.example.order.entity.OrderStatus;
import org.example.order.entity.OrderType;
import org.example.order.repository.OrderRepository.OrderPage;
import org.example.order.service.OrderService;
import org.example.shared.api.AuthenticatedUser;

@RestController
@RequestMapping(path = "/api/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Order> create(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CreateOrderCommand request) {
        var result = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/orders/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping
    public ResponseEntity<OrderPage> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) OrderStatus status, @RequestParam(required = false) OrderType orderType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(user, status, orderType, page, size));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId) {
        return ok(service.get(user, orderId));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Order> cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId) {
        return ok(service.cancel(user, orderId));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<Order> confirm(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId) {
        return ok(service.confirm(user, orderId));
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<Order> updateStatus(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long orderId, @RequestBody StatusRequest request) {
        return ok(service.updateStatus(user, orderId, request.status()));
    }

    private static ResponseEntity<Order> ok(Order result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
