package org.example.auth.web;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.example.auth.domain.account.*;
import org.example.auth.domain.exception.*;
import org.example.auth.service.AuthFailure;
import org.example.platform.security.LoginRateLimiter;
@RestControllerAdvice(assignableTypes = AuthController.class)
public class ApiErrors {
    public record Body(int status,String code,String message,String path,List<AuthFailure.FieldError> fieldErrors) {}
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<Body> auth(AuthFailure e,HttpServletRequest r) {
        int status=switch(e.code()) { case "INVALID_CREDENTIALS"->401;case "ACCOUNT_NOT_ACTIVE"->403;default->400;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new Body(status,e.code(),e.getMessage(),r.getRequestURI(),e.fields()));
    }
    @ExceptionHandler(DuplicateAccountException.class)
    ResponseEntity<Body> duplicate(HttpServletRequest r) { return error(r,409,"ACCOUNT_CONFLICT","Thông tin tài khoản đã được sử dụng."); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Body> json(HttpServletRequest r) { return error(r,400,"VALIDATION_ERROR","JSON/schema không hợp lệ."); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Body> media(HttpServletRequest r) { return error(r,415,"UNSUPPORTED_MEDIA_TYPE","Chỉ hỗ trợ application/json."); }
    @ExceptionHandler(LoginRateLimiter.LimitExceeded.class)
    ResponseEntity<Body> quota(LoginRateLimiter.LimitExceeded e,HttpServletRequest r) {
        return ResponseEntity.status(429).header("Retry-After",Long.toString(e.retryAfter())).cacheControl(CacheControl.noStore())
            .body(new Body(429,"LOGIN_RATE_LIMITED","Vượt giới hạn đăng nhập.",r.getRequestURI(),List.of()));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Body> missing(HttpServletRequest r) { return error(r,404,"NOT_FOUND","Không tìm thấy tài nguyên."); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Body> method(HttpServletRequest r) { return error(r,405,"METHOD_NOT_ALLOWED","Method không được hỗ trợ."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Body> internal(HttpServletRequest r) { return error(r,500,"INTERNAL_ERROR","Đã xảy ra lỗi hệ thống."); }
    static ResponseEntity<Body> error(HttpServletRequest r,int status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Body(status,code,message,r.getRequestURI(),List.of()));
    }
}
