package com.aftercare.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolResult(boolean success, JsonNode data, String errorCode, boolean retryable, String traceId) {
    public static ToolResult success(JsonNode data, String traceId) {
        return new ToolResult(true, data, null, false, traceId);
    }

    public static ToolResult failure(String code, boolean retryable, String traceId) {
        return new ToolResult(false, null, code, retryable, traceId);
    }
}
