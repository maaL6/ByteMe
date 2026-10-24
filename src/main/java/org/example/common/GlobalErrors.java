package org.example.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalErrors {
    private static final Logger log = LoggerFactory.getLogger(GlobalErrors.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException e, HttpServletRequest r) {
        int status = switch (e.type()) {
            case INVALID -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
        };
        return error(r, status, e.code(), e.getMessage());
    }

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

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest r) {
        log.warn("Vi phạm ràng buộc dữ liệu tại {}: {}", r.getRequestURI(), e.getMostSpecificCause().getMessage());
        return error(r, 409, "CONFLICT", "Dữ liệu trùng hoặc vi phạm ràng buộc.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpServletRequest r) {
        return error(r, 415, "UNSUPPORTED_MEDIA_TYPE", "Chỉ hỗ trợ application/json.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception e, HttpServletRequest r) {
        log.error("Lỗi không xử lý tại {}", r.getRequestURI(), e);
        return error(r, 500, "INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống.");
    }

    public static ResponseEntity<ApiError> error(HttpServletRequest r, int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiError(status, code, message, r.getRequestURI()));
    }
}
