package org.example.order.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.example.common.ApiError;
import org.example.common.GlobalErrors;
import org.example.order.service.OrderFailure;

/** Lỗi nghiệp vụ của Order; lỗi chung (JSON sai, 405, 409 lock, 415, 500...) do GlobalErrors xử lý. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderErrors {
    @ExceptionHandler(OrderFailure.class)
    ResponseEntity<ApiError> business(OrderFailure error, HttpServletRequest request) {
        int status = switch (error.code()) {
            case "FORBIDDEN" -> 403;
            case "ORDER_NOT_FOUND", "CART_NOT_FOUND", "FOOD_NOT_FOUND" -> 404;
            case "FOOD_UNAVAILABLE", "INSUFFICIENT_STOCK", "INVALID_ORDER_TRANSITION" -> 409;
            default -> 400;
        };
        return GlobalErrors.error(request, status, error.code(), error.getMessage());
    }
}
