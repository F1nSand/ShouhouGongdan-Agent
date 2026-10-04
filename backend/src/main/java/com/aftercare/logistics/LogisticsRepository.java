package com.aftercare.logistics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LogisticsRepository {
    private final JdbcTemplate jdbc;

    public LogisticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventRecord> findEvents(UUID tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT event_code, event_time, carrier_code, tracking_no, description
                FROM logistics_events
                WHERE tenant_id = ? AND order_id = ?
                ORDER BY event_time, id
                """, (rs, rowNum) -> new EventRecord(rs.getString("event_code"),
                rs.getTimestamp("event_time").toInstant(), rs.getString("carrier_code"),
                rs.getString("tracking_no"), rs.getString("description")), tenantId, orderId);
    }

    public record EventRecord(String eventCode, Instant eventTime, String carrierCode, String trackingNo,
            String description) {
    }
}
