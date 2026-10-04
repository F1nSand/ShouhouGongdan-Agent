package com.aftercare.approval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActionDraftRepository {
    private final JdbcTemplate jdbc;

    public ActionDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertManual(UUID tenantId, UUID draftId, UUID actorId, String payload,
            String payloadHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, 'CREATE_TICKET', CAST(? AS jsonb), ?, '{}'::jsonb, 'READY', ?)
                """, draftId, tenantId, actorId, payload, payloadHash, Timestamp.from(expiresAt));
    }

    public int insertAgent(UUID tenantId, UUID draftId, UUID runId, String invocationId,
            UUID conversationId, UUID actorId, String type, String payload, String payloadHash,
            String evidenceJson, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, run_id, invocation_id, conversation_id,
                    created_by, type, payload, payload_hash, evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'AGENT', ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), 'PREPARING', ?)
                ON CONFLICT (tenant_id, run_id, invocation_id) WHERE origin = 'AGENT' DO NOTHING
                """, draftId, tenantId, runId, invocationId, conversationId, actorId, type,
                payload, payloadHash, evidenceJson, Timestamp.from(expiresAt));
    }

    public Optional<DraftRecord> find(UUID tenantId, UUID draftId) {
        return query(tenantId, draftId, false);
    }

    public Optional<DraftRecord> findForUpdate(UUID tenantId, UUID draftId) {
        return query(tenantId, draftId, true);
    }

    public void markConfirmed(UUID tenantId, UUID draftId, String resultJson) {
        int changed = jdbc.update("""
                UPDATE action_drafts SET status = 'CONFIRMED', confirmed_result = CAST(? AS jsonb), updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'READY'
                """, resultJson, tenantId, draftId);
        if (changed != 1) {
            throw new IllegalStateException("Locked action draft changed before confirmation");
        }
    }

    public int invalidate(UUID tenantId, UUID draftId) {
        return jdbc.update("""
                UPDATE action_drafts SET status = 'INVALIDATED', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('PREPARING', 'READY')
                """, tenantId, draftId);
    }

    public int markReady(UUID tenantId, UUID draftId) {
        return jdbc.update("""
                UPDATE action_drafts SET status = 'READY', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PREPARING'
                """, tenantId, draftId);
    }

    public int expireUnconfirmed(Instant now) {
        return jdbc.update("""
                UPDATE action_drafts SET status = 'EXPIRED', updated_at = ?
                WHERE status IN ('PREPARING', 'READY') AND expires_at <= ?
                """, Timestamp.from(now), Timestamp.from(now));
    }

    public int invalidateRunDrafts(UUID tenantId, UUID runId) {
        return jdbc.update("""
                UPDATE action_drafts SET status = 'INVALIDATED', updated_at = now()
                WHERE tenant_id = ? AND run_id = ? AND status IN ('PREPARING', 'READY')
                """, tenantId, runId);
    }

    public boolean runBelongsToActor(UUID tenantId, UUID actorId, UUID conversationId, UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM agent_runs r JOIN conversations c
                      ON c.tenant_id = r.tenant_id AND c.id = r.conversation_id
                    WHERE r.tenant_id = ? AND r.id = ? AND r.actor_id = ?
                      AND r.conversation_id = ? AND c.owner_user_id = ?
                      AND r.status IN ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION')
                )
                """, Boolean.class, tenantId, runId, actorId, conversationId, actorId));
    }

    public Optional<UUID> runOwner(UUID tenantId, UUID actorId, UUID runId) {
        return jdbc.query("""
                SELECT id FROM agent_runs WHERE tenant_id = ? AND id = ? AND actor_id = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId, runId, actorId).stream().findFirst();
    }

    public Optional<DraftRecord> findAgent(UUID tenantId, UUID runId, String invocationId) {
        String sql = """
                SELECT id, tenant_id, origin, run_id, conversation_id, created_by, type, payload_hash,
                    payload::text AS payload_json, evidence_snapshot::text AS evidence_json,
                    status, expires_at, confirmed_result::text AS confirmed_result_json
                FROM action_drafts
                WHERE tenant_id = ? AND run_id = ? AND invocation_id = ? AND origin = 'AGENT'
                """;
        return query(sql, tenantId, runId, invocationId);
    }

    private Optional<DraftRecord> query(UUID tenantId, UUID draftId, boolean lock) {
        String sql = """
                SELECT id, tenant_id, origin, run_id, conversation_id, created_by, type, payload_hash,
                    payload::text AS payload_json,
                    evidence_snapshot::text AS evidence_json, status, expires_at,
                    confirmed_result::text AS confirmed_result_json
                FROM action_drafts WHERE tenant_id = ? AND id = ?
                """ + (lock ? " FOR UPDATE" : "");
        return query(sql, tenantId, draftId);
    }

    private Optional<DraftRecord> query(String sql, UUID tenantId, UUID id, Object... extra) {
        Object[] args = new Object[2 + extra.length];
        args[0] = tenantId;
        args[1] = id;
        System.arraycopy(extra, 0, args, 2, extra.length);
        return jdbc.query(sql, (rs, rowNum) -> new DraftRecord(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getString("origin"),
                rs.getObject("run_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getObject("created_by", UUID.class), rs.getString("type"), rs.getString("payload_hash"),
                rs.getString("payload_json"),
                rs.getString("evidence_json"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("confirmed_result_json")), args)
                .stream().findFirst();
    }

    public record DraftRecord(UUID id, UUID tenantId, String origin, UUID runId, UUID conversationId,
            UUID createdBy, String type, String payloadHash, String payloadJson, String evidenceJson, String status,
            Instant expiresAt, String confirmedResultJson) {
    }
}
