package com.aftercare.agent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean lockOwnedConversation(UUID tenantId, UUID actorId, UUID conversationId) {
        return !jdbc.query("""
                SELECT id FROM conversations
                WHERE tenant_id = ? AND id = ? AND owner_user_id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId, conversationId, actorId).isEmpty();
    }

    public Optional<RunRecord> findRequest(UUID tenantId, UUID actorId, UUID conversationId, UUID requestId) {
        return jdbc.query("""
                SELECT r.id, r.conversation_id, r.status, r.deadline_at, r.error_code,
                       (SELECT m.structured_answer::text FROM messages m
                         WHERE m.tenant_id = r.tenant_id AND m.run_id = r.id AND m.role = 'ASSISTANT'
                         ORDER BY m.created_at DESC LIMIT 1) AS answer_json
                FROM agent_runs r JOIN conversations c
                  ON c.tenant_id = r.tenant_id AND c.id = r.conversation_id
                WHERE r.tenant_id = ? AND r.actor_id = ? AND c.owner_user_id = ?
                  AND r.conversation_id = ? AND r.request_id = ?
                """, (rs, rowNum) -> new RunRecord(rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("status"),
                rs.getTimestamp("deadline_at").toInstant(), rs.getString("error_code"),
                rs.getString("answer_json")), tenantId, actorId, actorId, conversationId, requestId)
                .stream().findFirst();
    }

    public boolean hasActiveRun(UUID tenantId, UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_runs WHERE tenant_id = ? AND conversation_id = ?
                    AND status IN ('QUEUED', 'RUNNING'))
                """, Boolean.class, tenantId, conversationId));
    }

    public void insertRun(UUID tenantId, UUID runId, UUID conversationId, UUID actorId, UUID requestId,
            String inputHash, Instant deadline, String traceId) {
        jdbc.update("""
                INSERT INTO agent_runs(id, tenant_id, conversation_id, actor_id, request_id, input_hash,
                    status, deadline_at, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                """, runId, tenantId, conversationId, actorId, requestId, inputHash,
                Timestamp.from(deadline), traceId);
    }

    public void insertUserMessage(UUID tenantId, UUID conversationId, UUID runId, String content) {
        jdbc.update("""
                INSERT INTO messages(id, tenant_id, conversation_id, run_id, role, content)
                VALUES (?, ?, ?, ?, 'USER', ?)
                """, UUID.randomUUID(), tenantId, conversationId, runId, content);
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE tenant_id = ? AND id = ?",
                tenantId, conversationId);
    }

    public UUID insertAssistantMessage(UUID tenantId, UUID conversationId, UUID runId,
            String content, String structuredAnswerJson) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO messages(id, tenant_id, conversation_id, run_id, role, content, structured_answer)
                VALUES (?, ?, ?, ?, 'ASSISTANT', ?, CAST(? AS jsonb))
                """, messageId, tenantId, conversationId, runId, content, structuredAnswerJson);
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE tenant_id = ? AND id = ?",
                tenantId, conversationId);
        return messageId;
    }

    public void insertCitation(UUID citationId, UUID tenantId, UUID runId, UUID messageId,
            UUID chunkId, String snapshotJson) {
        jdbc.update("""
                INSERT INTO answer_citations(id, tenant_id, run_id, message_id, chunk_id, source_snapshot)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (tenant_id, run_id, message_id, chunk_id) DO NOTHING
                """, citationId, tenantId, runId, messageId, chunkId, snapshotJson);
    }

    public void insertToolInvocation(UUID tenantId, UUID runId, String invocationId, String toolName,
            String inputSummaryJson, String outputSummaryJson, String status, long durationMs, String errorCode) {
        jdbc.update("""
                INSERT INTO tool_invocations(id, tenant_id, run_id, invocation_id, tool_name,
                    input_summary, output_summary, status, duration_ms, error_code)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                ON CONFLICT (tenant_id, run_id, invocation_id) DO NOTHING
                """, UUID.randomUUID(), tenantId, runId, invocationId, toolName, inputSummaryJson,
                outputSummaryJson, status, durationMs, errorCode);
    }

    public int updateStatus(UUID tenantId, UUID runId, String status, String errorCode) {
        return jdbc.update("""
                UPDATE agent_runs SET status = ?, error_code = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, status, errorCode, tenantId, runId);
    }

    public int startRun(UUID tenantId, UUID runId) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'RUNNING', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'QUEUED'
                """, tenantId, runId);
    }

    public Optional<String> lockStatus(UUID tenantId, UUID runId) {
        return jdbc.query("SELECT status FROM agent_runs WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("status"), tenantId, runId).stream().findFirst();
    }

    public int completeRun(UUID tenantId, UUID runId, String status) {
        return jdbc.update("""
                UPDATE agent_runs SET status = ?, error_code = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                """, status, tenantId, runId);
    }

    public int cancelRun(UUID tenantId, UUID runId) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'CANCELLED', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION')
                """, tenantId, runId);
    }

    public int failRun(UUID tenantId, UUID runId, String errorCode) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'FAILED', error_code = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('QUEUED', 'RUNNING')
                """, errorCode, tenantId, runId);
    }

    public Optional<String> status(UUID tenantId, UUID runId) {
        return jdbc.query("SELECT status FROM agent_runs WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> rs.getString("status"), tenantId, runId).stream().findFirst();
    }

    public Optional<String> inputHash(UUID tenantId, UUID runId) {
        return jdbc.query("SELECT input_hash FROM agent_runs WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> rs.getString("input_hash"), tenantId, runId).stream().findFirst();
    }

    public Optional<UUID> conversationForOwnedRun(UUID tenantId, UUID actorId, UUID runId) {
        return jdbc.query("""
                SELECT r.conversation_id FROM agent_runs r JOIN conversations c
                  ON c.tenant_id = r.tenant_id AND c.id = r.conversation_id
                WHERE r.tenant_id = ? AND r.id = ? AND r.actor_id = ? AND c.owner_user_id = ?
                """, (rs, rowNum) -> rs.getObject("conversation_id", UUID.class),
                tenantId, runId, actorId, actorId).stream().findFirst();
    }

    public Optional<RunRecord> findRun(UUID tenantId, UUID actorId, UUID runId) {
        return jdbc.query("""
                SELECT r.id, r.conversation_id, r.status, r.deadline_at, r.error_code,
                       (SELECT m.structured_answer::text FROM messages m
                         WHERE m.tenant_id = r.tenant_id AND m.run_id = r.id AND m.role = 'ASSISTANT'
                         ORDER BY m.created_at DESC LIMIT 1) AS answer_json
                FROM agent_runs r JOIN conversations c
                  ON c.tenant_id = r.tenant_id AND c.id = r.conversation_id
                WHERE r.tenant_id = ? AND r.id = ? AND r.actor_id = ? AND c.owner_user_id = ?
                """, (rs, rowNum) -> new RunRecord(rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("status"),
                rs.getTimestamp("deadline_at").toInstant(), rs.getString("error_code"),
                rs.getString("answer_json")), tenantId, runId, actorId, actorId).stream().findFirst();
    }

    public long latestSequence(UUID tenantId, UUID runId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) FROM run_events WHERE tenant_id = ? AND run_id = ?
                """, Long.class, tenantId, runId);
        return value == null ? 0 : value;
    }

    public List<VisibleRunEvent> eventsAfter(UUID tenantId, UUID runId, long sequence, int limit) {
        return jdbc.query("""
                SELECT sequence, event_type, payload::text AS payload_json, created_at
                FROM run_events WHERE tenant_id = ? AND run_id = ? AND sequence > ?
                ORDER BY sequence LIMIT ?
                """, (rs, rowNum) -> new VisibleRunEvent(runId, rs.getLong("sequence"),
                rs.getString("event_type"), readTree(rs.getString("payload_json")),
                rs.getTimestamp("created_at").toInstant()), tenantId, runId, sequence, limit);
    }

    public boolean runIsTerminal(UUID tenantId, UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_CONFIRMATION')
                FROM agent_runs WHERE tenant_id = ? AND id = ?
                """, Boolean.class, tenantId, runId));
    }

    public List<RunIdentity> unfinishedRuns() {
        return jdbc.query("""
                SELECT id, tenant_id FROM agent_runs WHERE status IN ('QUEUED', 'RUNNING')
                """, (rs, rowNum) -> new RunIdentity(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class)));
    }

    public void insertConversation(UUID tenantId, UUID actorId, UUID conversationId, String title) {
        jdbc.update("""
                INSERT INTO conversations(id, tenant_id, owner_user_id, title, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, conversationId, tenantId, actorId, title);
    }

    public List<ConversationSummary> conversations(UUID tenantId, UUID actorId, int limit, int offset) {
        return jdbc.query("""
                SELECT c.id, c.title, c.status, c.created_at, c.updated_at,
                       (SELECT m.content FROM messages m WHERE m.tenant_id = c.tenant_id
                        AND m.conversation_id = c.id ORDER BY m.created_at DESC LIMIT 1) AS last_message
                FROM conversations c WHERE c.tenant_id = ? AND c.owner_user_id = ?
                ORDER BY c.updated_at DESC, c.id LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new ConversationSummary(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("last_message")),
                tenantId, actorId, limit, offset);
    }

    public List<MessageView> messages(UUID tenantId, UUID actorId, UUID conversationId, int limit) {
        if (!conversationOwned(tenantId, actorId, conversationId)) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, run_id, role, content, structured_answer::text AS answer_json, created_at
                FROM messages WHERE tenant_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, rowNum) -> new MessageView(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("role"), rs.getString("content"),
                rs.getString("answer_json") == null ? null : readTree(rs.getString("answer_json")),
                rs.getTimestamp("created_at").toInstant()), tenantId, conversationId, limit).reversed();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored run data is invalid", invalid);
        }
    }

    public boolean conversationOwned(UUID tenantId, UUID actorId, UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM conversations WHERE tenant_id = ? AND id = ? AND owner_user_id = ?)
                """, Boolean.class, tenantId, conversationId, actorId));
    }

    public record RunRecord(UUID id, UUID conversationId, String status, Instant deadlineAt,
            String errorCode, String answerJson) {
    }

    public record RunIdentity(UUID id, UUID tenantId) {
    }

    public record ConversationSummary(UUID id, String title, String status, Instant createdAt,
            Instant updatedAt, String lastMessage) {
    }

    public record MessageView(UUID id, UUID runId, String role, String content,
            com.fasterxml.jackson.databind.JsonNode structuredAnswer, Instant createdAt) {
    }
}
