package com.aftercare.audit;

import java.util.Map;
import java.util.UUID;

import com.aftercare.identity.ActorContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void success(ActorContext actor, String action, String resourceType, UUID resourceId,
            Map<String, ?> summary) {
        repository.append(actor.tenantId(), actor.actorId(), actor.role().name(), actor.traceId(),
                action, resourceType, resourceId, "SUCCESS", json(summary), null);
    }

    public void failure(ActorContext actor, String action, String resourceType, UUID resourceId,
            Map<String, ?> summary, String reason) {
        repository.append(actor.tenantId(), actor.actorId(), actor.role().name(), actor.traceId(),
                action, resourceType, resourceId, "FAILURE", json(summary), reason);
    }

    public void systemSuccess(UUID tenantId, String action, String resourceType, UUID resourceId,
            Map<String, ?> summary) {
        repository.append(tenantId, null, "SYSTEM", "outbox", action, resourceType, resourceId,
                "SUCCESS", json(summary), null);
    }

    public void systemFailure(UUID tenantId, String action, String resourceType, UUID resourceId,
            Map<String, ?> summary, String reason) {
        repository.append(tenantId, null, "SYSTEM", "outbox", action, resourceType, resourceId,
                "FAILURE", json(summary), reason);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Audit parameters could not be serialized", invalid);
        }
    }
}
