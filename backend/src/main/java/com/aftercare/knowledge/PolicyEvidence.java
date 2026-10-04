package com.aftercare.knowledge;

import java.util.List;
import java.util.UUID;

public record PolicyEvidence(List<Chunk> chunks) {
    public PolicyEvidence {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public record Chunk(UUID chunkId, String documentTitle, int versionNo, Integer pageNo, String content) {
    }
}
