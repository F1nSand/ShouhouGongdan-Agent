package com.aftercare.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(UUID tenantId, UUID actorId, String actorRole, String traceId,
            String action, String resourceType, UUID resourceId, String result,
            String parameterSummary, String reason) {
        jdbc.update("""
                INSERT INTO audit_events(id, tenant_id, actor_id, actor_role, trace_id, action,
                    resource_type, resource_id, result, parameter_summary, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), tenantId, actorId, actorRole, traceId, action,
                resourceType, resourceId, result, parameterSummary, reason);
    }

    public AuditPage list(UUID tenantId, String traceId, UUID resourceId, String action,
            Instant from, Instant to, int page, int size) {
        var where = new StringBuilder(" WHERE tenant_id = ?");
        var arguments = new ArrayList<Object>();
        arguments.add(tenantId);
        if (traceId != null && !traceId.isBlank()) {
            where.append(" AND trace_id = ?");
            arguments.add(traceId.trim());
        }
        if (resourceId != null) {
            where.append(" AND resource_id = ?");
            arguments.add(resourceId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND action = ?");
            arguments.add(action.trim());
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            arguments.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND created_at < ?");
            arguments.add(Timestamp.from(to));
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM audit_events" + where,
                Integer.class, arguments.toArray());
        var queryArguments = new ArrayList<>(arguments);
        queryArguments.add(size);
        queryArguments.add((page - 1) * size);
        List<AuditEventView> items = jdbc.query("""
                SELECT id, actor_id, actor_role, trace_id, action, resource_type, resource_id,
                       result, parameter_summary::text AS parameter_summary, reason, created_at
                FROM audit_events
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AuditEventView(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_role"),
                        rs.getString("trace_id"), rs.getString("action"), rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class), rs.getString("result"),
                        readSummary(rs.getString("parameter_summary")), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()), queryArguments.toArray());
        return new AuditPage(items, page, size, total == null ? 0 : total);
    }

    private JsonNode readSummary(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored audit summary is invalid", invalid);
        }
    }

    public record AuditPage(List<AuditEventView> items, int page, int size, int total) {
        public AuditPage { items = List.copyOf(items); }
    }
}
