package com.aftercare.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeVersionView(UUID documentId, UUID versionId, String title, String categoryCode,
        int versionNo, String status, Instant effectiveFrom, Instant effectiveTo, String failureReason,
        boolean reusedExistingVersion) {
}
