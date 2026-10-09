package org.example.order.web;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.example.order.service.OrderFailure;

@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderErrors {
    public record Body(int status, String code, String message, String path, List<Object> fieldErrors) {}

    @ExceptionHandler(OrderFailure.class)
    ResponseEntity<Body> business(OrderFailure error, HttpServletRequest request) {
        int status = switch (error.code()) {
            case "FORBIDDEN" -> 403;
            case "ORDER_NOT_FOUND", "CART_NOT_FOUND", "FOOD_NOT_FOUND" -> 404;
            case "FOOD_UNAVAILABLE", "INSUFFICIENT_STOCK", "INVALID_ORDER_TRANSITION" -> 409;
            case "CART_NOT_IMPLEMENTED" -> 503;
            default -> 400;
        };
        return error(request, status, error.code(), error.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Body> validation(HttpServletRequest request) {
        return error(request, 400, "VALIDATION_ERROR", "JSON hoặc tham số không hợp lệ.");
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<Body> conflict(HttpServletRequest request) {
        return error(request, 409, "ORDER_CONFLICT", "Đơn hàng hoặc món ăn đang được cập nhật; vui lòng thử lại.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Body> media(HttpServletRequest request) {
        return error(request, 415, "UNSUPPORTED_MEDIA_TYPE", "Chỉ hỗ trợ application/json.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Body> internal(HttpServletRequest request) {
        return error(request, 500, "INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống.");
    }

    private static ResponseEntity<Body> error(HttpServletRequest request, int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new Body(status, code, message, request.getRequestURI(), List.of()));
    }
}
