package org.example.cart.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.cart.dto.AddToCartRequest;
import org.example.cart.dto.CartDto;
import org.example.cart.dto.UpdateCartItemRequest;
import org.example.cart.service.CartService;
import org.example.shared.api.AuthenticatedUser;
import org.example.shared.api.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class CartController {

    private final CartService cartService;

    private long customerId(AuthenticatedUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (user.role() != Role.CUSTOMER) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer required");
        try {
            long id = Long.parseLong(user.userId());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid account ID");
    }

    @GetMapping
    public ResponseEntity<CartDto> getCart(@AuthenticationPrincipal AuthenticatedUser user) {
        Long userId = customerId(user);
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartDto> addToCart(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AddToCartRequest request) {
        Long userId = customerId(user);
        return ResponseEntity.ok(cartService.addToCart(userId, request));
    }

    @PutMapping("/items/{cartItemId}")
    public ResponseEntity<CartDto> updateCartItem(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        Long userId = customerId(user);
        return ResponseEntity.ok(cartService.updateCartItem(userId, cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<CartDto> removeCartItem(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long cartItemId) {
        Long userId = customerId(user);
        return ResponseEntity.ok(cartService.removeCartItem(userId, cartItemId));
    }
}
