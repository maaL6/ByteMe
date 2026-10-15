package org.example.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Lỗi chung cho mọi controller. Lỗi nghiệp vụ riêng (AuthFailure, OrderFailure)
 * được AuthErrors/OrderErrors xử lý trước, nhờ @Order ở các class đó.
 */
@RestControllerAdvice
public class GlobalErrors {

    /** Food/Cart ném ResponseStatusException; code suy ra từ status. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException e, HttpServletRequest r) {
        int status = e.getStatusCode().value();
        String code = switch (status) {
            case 400 -> "VALIDATION_ERROR";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "ERROR";
        };
        return error(r, status, code, e.getReason());
    }

    /** Lỗi @Valid: trả kèm danh sách field sai. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest r) {
        var fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return ResponseEntity.status(400).cacheControl(CacheControl.noStore())
                .body(new ApiError(400, "VALIDATION_ERROR", "Dữ liệu đầu vào không hợp lệ.", r.getRequestURI(), fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> badRequest(HttpServletRequest r) {
        return error(r, 400, "VALIDATION_ERROR", "JSON hoặc tham số không hợp lệ.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest r) {
        return error(r, 404, "NOT_FOUND", "Không tìm thấy tài nguyên.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpServletRequest r) {
        return error(r, 405, "METHOD_NOT_ALLOWED", "Method không được hỗ trợ.");
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<ApiError> conflict(HttpServletRequest r) {
        return error(r, 409, "CONFLICT", "Dữ liệu đang được cập nhật; vui lòng thử lại.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpServletRequest r) {
        return error(r, 415, "UNSUPPORTED_MEDIA_TYPE", "Chỉ hỗ trợ application/json.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(HttpServletRequest r) {
        return error(r, 500, "INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống.");
    }

    public static ResponseEntity<ApiError> error(HttpServletRequest r, int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiError(status, code, message, r.getRequestURI()));
    }
}
