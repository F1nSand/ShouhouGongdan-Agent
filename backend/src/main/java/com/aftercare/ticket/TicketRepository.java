package com.aftercare.ticket;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TicketRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, ticket_no, draft_id, source_run_id, order_id, category, title, description,
                priority, status, evidence_snapshot::text AS evidence_json, created_by, assigned_to,
                resolution, version, created_at, updated_at
            FROM tickets
            """;

    private final JdbcTemplate jdbc;

    public TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID tenantId, UUID ticketId, String ticketNo, UUID draftId, UUID sourceRunId,
            UUID orderId, String category, String title, String description, String priority,
            String evidenceJson, UUID createdBy) {
        jdbc.update("""
                INSERT INTO tickets(id, tenant_id, ticket_no, draft_id, source_run_id, order_id,
                    category, title, description, priority, status, evidence_snapshot, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', CAST(? AS jsonb), ?)
                """, ticketId, tenantId, ticketNo, draftId, sourceRunId, orderId,
                category, title, description, priority, evidenceJson, createdBy);
    }

    public Optional<TicketRecord> find(UUID tenantId, UUID ticketId) {
        return query(SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ?", tenantId, ticketId).stream().findFirst();
    }

    public Optional<TicketRecord> findForUpdate(UUID tenantId, UUID ticketId) {
        return query(SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ? FOR UPDATE", tenantId, ticketId)
                .stream().findFirst();
    }

    public List<TicketRecord> list(UUID tenantId, TicketStatus status, UUID orderId, int limit, int offset) {
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ?
                  AND (CAST(? AS varchar) IS NULL OR status = CAST(? AS varchar))
                  AND (CAST(? AS uuid) IS NULL OR order_id = CAST(? AS uuid))
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, TicketRepository::mapRecord, tenantId,
                status == null ? null : status.name(), status == null ? null : status.name(),
                orderId, orderId, limit, offset);
    }

    public int count(UUID tenantId, TicketStatus status, UUID orderId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM tickets WHERE tenant_id = ?
                  AND (CAST(? AS varchar) IS NULL OR status = CAST(? AS varchar))
                  AND (CAST(? AS uuid) IS NULL OR order_id = CAST(? AS uuid))
                """, Integer.class, tenantId,
                status == null ? null : status.name(), status == null ? null : status.name(),
                orderId, orderId);
        return count == null ? 0 : count;
    }

    public int assign(UUID tenantId, UUID ticketId, UUID assigneeId, long expectedVersion) {
        return jdbc.update("""
                UPDATE tickets SET status = 'ASSIGNED', assigned_to = ?, version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'OPEN' AND version = ?
                """, assigneeId, tenantId, ticketId, expectedVersion);
    }

    public int transition(UUID tenantId, UUID ticketId, TicketStatus target, String resolution,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE tickets SET status = ?, resolution = COALESCE(?, resolution),
                    version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, target.name(), resolution, tenantId, ticketId, expectedVersion);
    }

    public boolean hasPendingSensitiveAction(UUID tenantId, UUID ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sensitive_actions
                    WHERE tenant_id = ? AND ticket_id = ?
                      AND status NOT IN ('REJECTED', 'EXPIRED', 'SUCCEEDED', 'SIMULATED_SUCCEEDED', 'FAILED'))
                """, Boolean.class, tenantId, ticketId));
    }

    public Optional<Assignee> findAssignableUser(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT role, status FROM users WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> new Assignee(rs.getString("role"), rs.getString("status")),
                tenantId, userId).stream().findFirst();
    }

    public void insertEvent(UUID tenantId, UUID ticketId, UUID actorId, String eventType,
            TicketStatus fromStatus, TicketStatus toStatus, String comment) {
        jdbc.update("""
                INSERT INTO ticket_events(id, tenant_id, ticket_id, actor_id, event_type,
                    from_status, to_status, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, ticketId, actorId, eventType,
                fromStatus == null ? null : fromStatus.name(), toStatus == null ? null : toStatus.name(), comment);
    }

    public List<TicketEventView> events(UUID tenantId, UUID ticketId) {
        return jdbc.query("""
                SELECT id, actor_id, event_type, from_status, to_status, comment, created_at
                FROM ticket_events WHERE tenant_id = ? AND ticket_id = ?
                ORDER BY created_at, id
                """, (rs, rowNum) -> new TicketEventView(rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class), rs.getString("event_type"),
                status(rs.getString("from_status")), status(rs.getString("to_status")),
                rs.getString("comment"), instant(rs.getTimestamp("created_at"))), tenantId, ticketId);
    }

    private List<TicketRecord> query(String sql, UUID tenantId, UUID ticketId) {
        return jdbc.query(sql, TicketRepository::mapRecord, tenantId, ticketId);
    }

    private static TicketRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TicketRecord(rs.getObject("id", UUID.class), rs.getString("ticket_no"),
                rs.getObject("draft_id", UUID.class), rs.getObject("source_run_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("category"), rs.getString("title"),
                rs.getString("description"), rs.getString("priority"), TicketStatus.valueOf(rs.getString("status")),
                rs.getString("evidence_json"), rs.getObject("created_by", UUID.class),
                rs.getObject("assigned_to", UUID.class), rs.getString("resolution"), rs.getLong("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static TicketStatus status(String value) {
        return value == null ? null : TicketStatus.valueOf(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record TicketRecord(UUID id, String ticketNo, UUID draftId, UUID sourceRunId, UUID orderId,
            String category, String title, String description, String priority, TicketStatus status,
            String evidenceJson, UUID createdBy, UUID assignedTo, String resolution, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record Assignee(String role, String status) {
    }
}
