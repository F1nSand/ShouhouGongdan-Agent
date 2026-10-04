package com.aftercare.agent;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record VisibleRunEvent(UUID runId, long sequence, String eventType, JsonNode payload, Instant createdAt) {
    public VisibleRunEvent {
        payload = payload == null ? null : payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload == null ? null : payload.deepCopy();
    }
}
