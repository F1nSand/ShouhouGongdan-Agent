package com.aftercare.common;

import java.util.Map;

import com.aftercare.identity.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiResponse.error(exception.code(), null));
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleCredentials(
            AuthService.InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("UNAUTHENTICATED", Map.of("message", exception.getMessage())));
    }

    @ExceptionHandler(AuthService.RateLimitedException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleRateLimit(AuthService.RateLimitedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error("RATE_LIMITED", Map.of("message", exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst().map(FieldError::getDefaultMessage).orElse("请求参数无效");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_ARGUMENT", Map.of("message", message)));
    }
}
