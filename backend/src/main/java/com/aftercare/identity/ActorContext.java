package com.aftercare.identity;

import java.util.UUID;

public record ActorContext(UUID tenantId, UUID actorId, Role role, String traceId) {
}
