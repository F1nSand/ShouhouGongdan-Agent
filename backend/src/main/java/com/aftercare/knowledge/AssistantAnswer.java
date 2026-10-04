package com.aftercare.knowledge;

import java.util.List;
import java.util.UUID;

public record AssistantAnswer(String answer, List<Citation> citations) {
    public AssistantAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public record Citation(UUID chunkId, String quote) {
    }
}
