package com.aftercare.agent;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentRunView(UUID runId, UUID conversationId, String status, Instant deadlineAt,
        String errorCode, JsonNode structuredAnswer, String eventsUrl) {
    public AgentRunView {
        structuredAnswer = structuredAnswer == null ? null : structuredAnswer.deepCopy();
    }

    @Override
    public JsonNode structuredAnswer() {
        return structuredAnswer == null ? null : structuredAnswer.deepCopy();
    }
}
