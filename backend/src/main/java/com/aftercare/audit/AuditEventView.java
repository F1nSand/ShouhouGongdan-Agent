package com.aftercare.audit;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record AuditEventView(UUID id, UUID actorId, String actorRole, String traceId,
        String action, String resourceType, UUID resourceId, String result,
        JsonNode parameterSummary, String reason, Instant createdAt) {
}
