package com.aftercare.approval;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.aftercare.identity.Role;
import com.aftercare.jobs.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.action-worker.enabled=false", "app.knowledge.worker-enabled=false"})
@ActiveProfiles("mock")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActionRecoveryIT {
    private static final String JWT_SECRET = "bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==";

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aftercare")
            .withUsername("aftercare_migrator")
            .withPassword("local-migration-password");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("app.security.jwt-secret", () -> JWT_SECRET);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ActionExecutor actionExecutor;

    @Autowired
    private OutboxRepository outbox;

    private UUID tenantId;
    private UUID requesterId;

    @BeforeEach
    void insertTenantAndRequester() {
        tenantId = UUID.randomUUID();
        requesterId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Recovery test', 'ACTIVE')",
                tenantId, "RECOVERY_" + tenantId.toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', ?, 'ACTIVE')
                """, requesterId, tenantId, "requester_" + requesterId.toString().substring(0, 8),
                Role.CUSTOMER_SERVICE.name());
    }

    @Test
    void duplicateExecutionHasOneEffect() {
        UUID skuId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO skus(id, tenant_id, sku_code, name, category_code, status)
                VALUES (?, ?, 'SKU-RECOVERY', 'Recovery item', 'GENERAL', 'ACTIVE')
                """, skuId, tenantId);
        jdbc.update("""
                INSERT INTO warehouses(id, tenant_id, code, name, region_code, status)
                VALUES (?, ?, 'WH-RECOVERY-S', 'Source', 'EAST', 'ACTIVE'),
                       (?, ?, 'WH-RECOVERY-T', 'Target', 'WEST', 'ACTIVE')
                """, sourceId, tenantId, targetId, tenantId);
        jdbc.update("""
                INSERT INTO warehouse_routes(id, tenant_id, source_warehouse_id, target_warehouse_id,
                    estimated_days, cost_cents_per_unit)
                VALUES (?, ?, ?, ?, 1, 50)
                """, UUID.randomUUID(), tenantId, sourceId, targetId);
        jdbc.update("""
                INSERT INTO inventory(id, tenant_id, warehouse_id, sku_id, on_hand, reserved, safety_stock)
                VALUES (?, ?, ?, ?, 3, 0, 0), (?, ?, ?, ?, 0, 0, 0)
                """, UUID.randomUUID(), tenantId, sourceId, skuId,
                UUID.randomUUID(), tenantId, targetId, skuId);
        UUID actionId = createAction("STOCK_TRANSFER", "{\"skuId\":\"" + skuId
                + "\",\"sourceWarehouseId\":\"" + sourceId + "\",\"targetWarehouseId\":\""
                + targetId + "\",\"quantity\":1}");

        ExecutionResult first = actionExecutor.execute(tenantId, actionId);
        ExecutionResult replay = actionExecutor.execute(tenantId, actionId);

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.transferOrderId()).isNotNull();
        assertThat(replay.transferOrderId()).isEqualTo(first.transferOrderId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_orders WHERE tenant_id = ? AND action_id = ?",
                Integer.class, tenantId, actionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE tenant_id = ? AND warehouse_id = ?",
                Integer.class, tenantId, sourceId)).isEqualTo(1);
    }

    @Test
    void workerCrashCanBeRecoveredAfterLease() {
        UUID actionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_jobs(id, tenant_id, aggregate_id, job_type, status)
                VALUES (?, ?, ?, 'SENSITIVE_ACTION_EXECUTION', 'READY')
                """, jobId, tenantId, actionId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(1);

        var firstLease = outbox.claim("worker-a", now).orElseThrow();
        assertThat(firstLease.attempts()).isEqualTo(1);
        assertThat(outbox.claim("worker-b", now.plusSeconds(59))).isEmpty();
        var recoveredLease = outbox.claim("worker-b", now.plusSeconds(61)).orElseThrow();

        assertThat(recoveredLease.leaseVersion()).isGreaterThan(firstLease.leaseVersion());
        assertThat(recoveredLease.attempts()).isEqualTo(2);
        assertThat(outbox.complete(jobId, "worker-a", firstLease.leaseVersion())).isFalse();
        assertThat(outbox.complete(jobId, "worker-b", recoveredLease.leaseVersion())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_jobs WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, jobId)).isEqualTo("DONE");
    }

    @Test
    void retriesUseOneFiveAndThirtySecondBackoff() {
        UUID actionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_jobs(id, tenant_id, aggregate_id, job_type, status)
                VALUES (?, ?, ?, 'SENSITIVE_ACTION_EXECUTION', 'READY')
                """, jobId, tenantId, actionId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(2);

        var first = outbox.claim("worker-retry", now).orElseThrow();
        assertThat(outbox.rescheduleOrFail(first, "TEMPORARY", now).retryScheduled()).isTrue();
        assertThat(availableAt(jobId)).isEqualTo(now.plusSeconds(1));
        var second = outbox.claim("worker-retry", now.plusSeconds(1)).orElseThrow();
        assertThat(outbox.rescheduleOrFail(second, "TEMPORARY", now.plusSeconds(1)).retryScheduled()).isTrue();
        assertThat(availableAt(jobId)).isEqualTo(now.plusSeconds(6));
        var third = outbox.claim("worker-retry", now.plusSeconds(6)).orElseThrow();
        assertThat(outbox.rescheduleOrFail(third, "TEMPORARY", now.plusSeconds(6)).retryScheduled()).isTrue();
        assertThat(availableAt(jobId)).isEqualTo(now.plusSeconds(36));
        var fourth = outbox.claim("worker-retry", now.plusSeconds(36)).orElseThrow();
        var exhausted = outbox.rescheduleOrFail(fourth, "TEMPORARY", now.plusSeconds(36));

        assertThat(exhausted.leaseOwned()).isTrue();
        assertThat(exhausted.retryScheduled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_jobs WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, jobId)).isEqualTo("FAILED");
    }

    private UUID createAction(String type, String payload) {
        UUID draftId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        String draftType = "STOCK_TRANSFER".equals(type) ? "REQUEST_STOCK_TRANSFER" : "REQUEST_REFUND";
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, ?, CAST(? AS jsonb), ?, '{}'::jsonb, 'CONFIRMED', ?)
                """, draftId, tenantId, requesterId, draftType, payload, "c".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO sensitive_actions(id, tenant_id, draft_id, requested_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, '{}'::jsonb, 'APPROVED', ?)
                """, actionId, tenantId, draftId, requesterId, type, payload, "c".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return actionId;
    }

    private Instant availableAt(UUID jobId) {
        return jdbc.queryForObject("SELECT available_at FROM outbox_jobs WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), tenantId, jobId);
    }
}
