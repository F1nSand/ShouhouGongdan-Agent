package com.aftercare.approval;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record DraftView(UUID id, String origin, UUID runId, String type, JsonNode payload,
        JsonNode evidenceSnapshot, String status, Instant expiresAt) {
    public DraftView {
        payload = payload == null ? null : payload.deepCopy();
        evidenceSnapshot = evidenceSnapshot == null ? null : evidenceSnapshot.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload == null ? null : payload.deepCopy();
    }

    @Override
    public JsonNode evidenceSnapshot() {
        return evidenceSnapshot == null ? null : evidenceSnapshot.deepCopy();
    }
}
