package org.example.cart.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.cart.dto.AddToCartRequest;
import org.example.cart.dto.CartDto;
import org.example.cart.dto.UpdateCartItemRequest;
import org.example.cart.service.CartService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    // Simulate logged-in user via header since Auth is not fully implemented
    private Long getUserIdFromHeader(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized - X-User-Id header is missing");
        }
        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid User ID");
        }
    }

    @GetMapping
    public ResponseEntity<CartDto> getCart(@RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = getUserIdFromHeader(userIdHeader);
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartDto> addToCart(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @Valid @RequestBody AddToCartRequest request) {
        Long userId = getUserIdFromHeader(userIdHeader);
        return ResponseEntity.ok(cartService.addToCart(userId, request));
    }

    @PutMapping("/items/{cartItemId}")
    public ResponseEntity<CartDto> updateCartItem(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        Long userId = getUserIdFromHeader(userIdHeader);
        return ResponseEntity.ok(cartService.updateCartItem(userId, cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<CartDto> removeCartItem(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable Long cartItemId) {
        Long userId = getUserIdFromHeader(userIdHeader);
        return ResponseEntity.ok(cartService.removeCartItem(userId, cartItemId));
    }
}
