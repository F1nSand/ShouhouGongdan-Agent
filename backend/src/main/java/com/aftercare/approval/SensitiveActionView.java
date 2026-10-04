package com.aftercare.approval;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record SensitiveActionView(UUID id, UUID ticketId, String type, JsonNode payload,
        JsonNode evidenceSnapshot, String status, Instant expiresAt, long version,
        UUID requestedBy, JsonNode executionResult, String errorCode) {
    public SensitiveActionView {
        payload = payload == null ? null : payload.deepCopy();
        evidenceSnapshot = evidenceSnapshot == null ? null : evidenceSnapshot.deepCopy();
        executionResult = executionResult == null ? null : executionResult.deepCopy();
    }
}
