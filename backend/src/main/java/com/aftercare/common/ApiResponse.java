package com.aftercare.common;

import org.slf4j.MDC;

public record ApiResponse<T>(String code, T data, String traceId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", data, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> error(String code, T data) {
        return new ApiResponse<>(code, data, MDC.get("traceId"));
    }
}
