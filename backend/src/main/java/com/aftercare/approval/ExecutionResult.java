package com.aftercare.approval;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ExecutionResult(UUID actionId, String status, boolean simulation,
        UUID transferOrderId, String errorCode, String executionMode, JsonNode details) {
}
