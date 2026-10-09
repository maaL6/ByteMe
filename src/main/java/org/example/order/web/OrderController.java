package org.example.order.web;

import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.example.order.domain.OrderStatus;
import org.example.order.domain.OrderType;
import org.example.order.domain.Order;
import org.example.order.service.port.OrderRepository.OrderPage;
import org.example.order.service.CreateOrderCommand;
import org.example.order.service.OrderService;
import org.example.shared.api.AuthenticatedUser;

@RestController
@Profile("auth")
@RequestMapping(path = "/api/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {
    public record StatusRequest(OrderStatus status) {}
    private final OrderService service;

    public OrderController(OrderService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Tạo đơn từ giỏ hàng (503 khi Cart Service còn là placeholder)")
    public ResponseEntity<Order> create(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CreateOrderCommand request) {
        var result = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/orders/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping
    @Operation(summary = "Khách xem đơn của mình; nhân viên xem đơn cần xử lý")
    public ResponseEntity<OrderPage> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) OrderStatus status, @RequestParam(required = false) OrderType orderType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(user, status, orderType, page, size));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Xem đơn hàng và snapshot chi tiết món")
    public ResponseEntity<Order> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId) {
        return ok(service.get(user, orderId));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Khách hủy đơn PENDING; giữ lịch sử và hoàn kho")
    public ResponseEntity<Order> cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId) {
        return ok(service.cancel(user, orderId));
    }

    @PostMapping("/{orderId}/confirm")
    @Operation(summary = "Nhân viên xác nhận đơn PENDING")
    public ResponseEntity<Order> confirm(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId) {
        return ok(service.confirm(user, orderId));
    }

    @PutMapping("/{orderId}/status")
    @Operation(summary = "Nhân viên chuyển trạng thái hoặc hủy đơn")
    public ResponseEntity<Order> updateStatus(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long orderId, @RequestBody StatusRequest request) {
        return ok(service.updateStatus(user, orderId, request.status()));
    }

    private static ResponseEntity<Order> ok(Order result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
