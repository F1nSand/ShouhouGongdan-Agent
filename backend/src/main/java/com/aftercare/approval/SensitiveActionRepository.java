package com.aftercare.approval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SensitiveActionRepository {
    private final JdbcTemplate jdbc;

    public SensitiveActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID tenantId, UUID actionId, UUID draftId, UUID ticketId, UUID requestedBy,
            String type, String payload, String payloadHash, String evidence, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO sensitive_actions(id, tenant_id, draft_id, ticket_id, requested_by, type,
                    payload, payload_hash, evidence_snapshot, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), 'PENDING_APPROVAL', ?)
                """, actionId, tenantId, draftId, ticketId, requestedBy, type,
                payload, payloadHash, evidence, Timestamp.from(expiresAt));
    }

    public Optional<ActionRecord> find(UUID tenantId, UUID actionId) {
        return query(tenantId, actionId, false);
    }

    public Optional<ActionRecord> findForUpdate(UUID tenantId, UUID actionId) {
        return query(tenantId, actionId, true);
    }

    public int decide(UUID tenantId, UUID actionId, String status, long expectedVersion) {
        return jdbc.update("""
                UPDATE sensitive_actions SET status = ?, version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL' AND version = ?
                """, status, tenantId, actionId, expectedVersion);
    }

    public void insertDecision(UUID tenantId, UUID actionId, UUID decidedBy, String decision,
            String reason, String payloadHash) {
        jdbc.update("""
                INSERT INTO approval_decisions(id, tenant_id, action_id, decided_by, decision, reason, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actionId, decidedBy, decision, reason, payloadHash);
    }

    public void enqueueExecution(UUID tenantId, UUID actionId) {
        jdbc.update("""
                INSERT INTO outbox_jobs(id, tenant_id, aggregate_id, job_type, status)
                VALUES (?, ?, ?, 'SENSITIVE_ACTION_EXECUTION', 'READY')
                ON CONFLICT (tenant_id, aggregate_id, job_type) DO NOTHING
                """, UUID.randomUUID(), tenantId, actionId);
    }

    public int markExecuting(UUID tenantId, UUID actionId) {
        return jdbc.update("""
                UPDATE sensitive_actions SET status = 'EXECUTING', version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'APPROVED'
                """, tenantId, actionId);
    }

    public int markSucceeded(UUID tenantId, UUID actionId, String status, String resultJson) {
        return jdbc.update("""
                UPDATE sensitive_actions SET status = ?, execution_result = CAST(? AS jsonb), error_code = NULL,
                    version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'EXECUTING'
                """, status, resultJson, tenantId, actionId);
    }

    public int markFailed(UUID tenantId, UUID actionId, String errorCode, String resultJson) {
        return jdbc.update("""
                UPDATE sensitive_actions SET status = 'FAILED', error_code = ?,
                    execution_result = CAST(? AS jsonb), version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('APPROVED', 'EXECUTING')
                """, errorCode, resultJson, tenantId, actionId);
    }

    public int markExpired(UUID tenantId, UUID actionId) {
        return jdbc.update("""
                UPDATE sensitive_actions SET status = 'EXPIRED', version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('PENDING_APPROVAL', 'APPROVED')
                """, tenantId, actionId);
    }

    public List<ActionRecord> findExpiredForUpdate(Instant now) {
        return jdbc.query(selectColumns() + " WHERE status IN ('PENDING_APPROVAL', 'APPROVED')"
                + " AND expires_at <= ? ORDER BY expires_at, id FOR UPDATE SKIP LOCKED",
                this::map, Timestamp.from(now));
    }

    public List<ActionRecord> listOwned(UUID tenantId, UUID actorId, int limit, int offset) {
        return jdbc.query(selectColumns() + " WHERE tenant_id = ? AND requested_by = ?"
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", this::map,
                tenantId, actorId, limit, offset);
    }

    public List<ActionRecord> listAll(UUID tenantId, int limit, int offset) {
        return jdbc.query(selectColumns() + " WHERE tenant_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                this::map, tenantId, limit, offset);
    }

    public List<ActionRecord> listApprovable(UUID tenantId, String type, int limit, int offset) {
        return jdbc.query(selectColumns() + " WHERE tenant_id = ? AND type = ?"
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", this::map,
                tenantId, type, limit, offset);
    }

    private Optional<ActionRecord> query(UUID tenantId, UUID actionId, boolean lock) {
        String sql = selectColumns() + " WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, this::map, tenantId, actionId).stream().findFirst();
    }

    private String selectColumns() {
        return "SELECT id, tenant_id, draft_id, ticket_id, requested_by, type, payload::text AS payload_json, "
                + "payload_hash, evidence_snapshot::text AS evidence_json, status, expires_at, "
                + "execution_result::text AS execution_json, error_code, version FROM sensitive_actions";
    }

    private ActionRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp resultTimestamp = rs.getTimestamp("expires_at");
        return new ActionRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("draft_id", UUID.class), rs.getObject("ticket_id", UUID.class),
                rs.getObject("requested_by", UUID.class), rs.getString("type"), rs.getString("payload_json"),
                rs.getString("payload_hash"), rs.getString("evidence_json"), rs.getString("status"),
                resultTimestamp.toInstant(), rs.getString("execution_json"), rs.getString("error_code"),
                rs.getLong("version"));
    }

    public record ActionRecord(UUID id, UUID tenantId, UUID draftId, UUID ticketId, UUID requestedBy,
            String type, String payloadJson, String payloadHash, String evidenceJson,
            String status, Instant expiresAt, String executionJson, String errorCode, long version) {
    }
}
