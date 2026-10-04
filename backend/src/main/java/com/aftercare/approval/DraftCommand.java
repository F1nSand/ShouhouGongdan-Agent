package com.aftercare.approval;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record DraftCommand(String invocationId, UUID conversationId, String type,
        JsonNode payload, JsonNode evidenceSnapshot) {
    public DraftCommand {
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
