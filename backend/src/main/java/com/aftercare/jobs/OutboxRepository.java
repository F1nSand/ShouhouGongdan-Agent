package com.aftercare.jobs;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
    private static final long[] RETRY_DELAYS_SECONDS = {1, 5, 30};

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<OutboxJob> claim(String workerId, Instant now) {
        var jobs = jdbc.query("""
                SELECT id, tenant_id, aggregate_id, attempts, lease_version
                FROM outbox_jobs
                WHERE job_type = 'SENSITIVE_ACTION_EXECUTION'
                  AND available_at <= ?
                  AND (status = 'READY' OR (status = 'LEASED' AND lease_until <= ?))
                ORDER BY available_at, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """, (rs, rowNum) -> new OutboxJob(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                rs.getInt("attempts"), rs.getLong("lease_version"), workerId,
                now.plus(LEASE_DURATION)), Timestamp.from(now), Timestamp.from(now));
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        OutboxJob selected = jobs.getFirst();
        int changed = jdbc.update("""
                UPDATE outbox_jobs SET status = 'LEASED', attempts = attempts + 1,
                    lease_owner = ?, lease_until = ?, lease_version = lease_version + 1, updated_at = ?
                WHERE id = ?
                """, workerId, Timestamp.from(selected.leaseUntil()), Timestamp.from(now), selected.id());
        if (changed != 1) {
            throw new IllegalStateException("Locked outbox job could not be leased");
        }
        return Optional.of(new OutboxJob(selected.id(), selected.tenantId(), selected.aggregateId(),
                selected.attempts() + 1, selected.leaseVersion() + 1, workerId, selected.leaseUntil()));
    }

    public boolean complete(UUID jobId, String workerId, long leaseVersion) {
        return jdbc.update("""
                UPDATE outbox_jobs SET status = 'DONE', lease_owner = NULL, lease_until = NULL,
                    last_error = NULL, updated_at = now()
                WHERE id = ? AND status = 'LEASED' AND lease_owner = ? AND lease_version = ?
                """, jobId, workerId, leaseVersion) == 1;
    }

    public boolean renew(UUID jobId, String workerId, long leaseVersion, Instant now) {
        return jdbc.update("""
                UPDATE outbox_jobs SET lease_until = ?, updated_at = ?
                WHERE id = ? AND status = 'LEASED' AND lease_owner = ? AND lease_version = ?
                """, Timestamp.from(now.plus(LEASE_DURATION)), Timestamp.from(now),
                jobId, workerId, leaseVersion) == 1;
    }

    @Transactional
    public FailureDisposition rescheduleOrFail(OutboxJob job, String errorCode, Instant now) {
        var attempts = jdbc.query("""
                SELECT attempts FROM outbox_jobs
                WHERE id = ? AND status = 'LEASED' AND lease_owner = ? AND lease_version = ?
                FOR UPDATE
                """, (rs, rowNum) -> rs.getInt("attempts"), job.id(), job.leaseOwner(), job.leaseVersion());
        if (attempts.isEmpty()) {
            return new FailureDisposition(false, false);
        }
        int attempt = attempts.getFirst();
        if (attempt <= RETRY_DELAYS_SECONDS.length) {
            Instant availableAt = now.plusSeconds(RETRY_DELAYS_SECONDS[attempt - 1]);
            jdbc.update("""
                    UPDATE outbox_jobs SET status = 'READY', available_at = ?, lease_owner = NULL,
                        lease_until = NULL, last_error = ?, updated_at = ?
                    WHERE id = ? AND status = 'LEASED' AND lease_owner = ? AND lease_version = ?
                    """, Timestamp.from(availableAt), errorCode, Timestamp.from(now),
                    job.id(), job.leaseOwner(), job.leaseVersion());
            return new FailureDisposition(true, true);
        }
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'FAILED', lease_owner = NULL, lease_until = NULL,
                    last_error = ?, updated_at = ?
                WHERE id = ? AND status = 'LEASED' AND lease_owner = ? AND lease_version = ?
                """, errorCode, Timestamp.from(now), job.id(), job.leaseOwner(), job.leaseVersion());
        return new FailureDisposition(true, false);
    }

    public record OutboxJob(UUID id, UUID tenantId, UUID aggregateId, int attempts,
            long leaseVersion, String leaseOwner, Instant leaseUntil) {
    }

    public record FailureDisposition(boolean leaseOwned, boolean retryScheduled) {
    }
}
