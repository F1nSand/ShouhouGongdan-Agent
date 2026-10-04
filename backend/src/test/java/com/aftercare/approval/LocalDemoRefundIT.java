package com.aftercare.approval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aftercare.common.ApiException;
import com.aftercare.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.action-worker.enabled=false", "app.knowledge.worker-enabled=false",
        "app.refund.mode=local-demo"})
@ActiveProfiles("mock")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalDemoRefundIT {
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ActionExecutor executor;

    private UUID tenantId;
    private UUID requesterId;
    private UUID orderId;

    @BeforeEach
    void createOrder() {
        tenantId = UUID.randomUUID();
        requesterId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Refund demo test', 'ACTIVE')",
                tenantId, "LOCAL_REFUND_" + tenantId.toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', ?, 'ACTIVE')
                """, requesterId, tenantId, "requester_" + requesterId.toString().substring(0, 8),
                Role.CUSTOMER_SERVICE.name());
        jdbc.update("""
                INSERT INTO customers(id, tenant_id, display_name, phone_masked, external_ref)
                VALUES (?, ?, 'Demo customer', '***', ?)
                """, customerId, tenantId, "customer-" + customerId);
        jdbc.update("""
                INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at,
                    paid_amount_cents, refunded_amount_cents, promised_delivery_at)
                VALUES (?, ?, ?, ?, 'PAID', ?, 1000, 100, ?)
                """, orderId, tenantId, "ORDER-" + orderId, customerId,
                Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    @Test
    void recordsRefundExactlyOnceAndMakesDemoBoundaryExplicit() {
        UUID actionId = addAction("APPROVED", 200);

        ExecutionResult first = executor.execute(tenantId, actionId);
        ExecutionResult replay = executor.execute(tenantId, actionId);

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.executionMode()).isEqualTo("LOCAL_DEMO_LEDGER");
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.executionMode()).isEqualTo(first.executionMode());
        assertThat(replay.details().path("refundedTotalCents").asLong()).isEqualTo(300L);
        assertThat(refundedAmount()).isEqualTo(300L);
        assertThat(count("refund_ledger", actionId)).isEqualTo(1);
        assertThat(countAudit("REFUND_LOCAL_DEMO_RECORDED", actionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sensitive_actions WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, actionId)).isEqualTo("SUCCEEDED");
        String executionJson = jdbc.queryForObject("SELECT execution_result::text FROM sensitive_actions "
                + "WHERE tenant_id = ? AND id = ?", String.class, tenantId, actionId);
        assertThat(executionJson).contains("LOCAL_DEMO_LEDGER").contains("演示订单退款已入账")
                .contains("externalPayment").contains("false");
    }

    @Test
    void fullRefundLeavesOrderLogisticsStatusIndependent() {
        UUID actionId = addAction("APPROVED", 900);

        ExecutionResult result = executor.execute(tenantId, actionId);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(refundedAmount()).isEqualTo(1000L);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, orderId)).isEqualTo("PAID");
    }

    @Test
    void concurrentDifferentActionsCannotRefundMoreThanPaidAmount() throws Exception {
        UUID firstAction = addAction("APPROVED", 500);
        UUID secondAction = addAction("APPROVED", 500);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<ExecutionResult>>();
            tasks.add(pool.submit(() -> executeAtStart(ready, start, firstAction)));
            tasks.add(pool.submit(() -> executeAtStart(ready, start, secondAction)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.List.of(tasks.get(0).get(30, TimeUnit.SECONDS),
                    tasks.get(1).get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.status().equals("SUCCEEDED")).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.status().equals("FAILED")
                    && "REFUND_AMOUNT_INVALID".equals(result.errorCode())).count()).isEqualTo(1);
        }
        assertThat(refundedAmount()).isEqualTo(600L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_ledger WHERE tenant_id = ?", Integer.class,
                tenantId)).isEqualTo(1);
    }

    @Test
    void rejectedOrUnapprovedAndCrossTenantActionsDoNotChangeTheOrder() {
        UUID pending = addAction("PENDING_APPROVAL", 200);
        UUID rejected = addAction("REJECTED", 200);
        UUID wrongTenant = UUID.randomUUID();

        assertThatThrownBy(() -> executor.execute(tenantId, pending))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("ACTION_NOT_APPROVED");
        assertThat(executor.execute(tenantId, rejected).status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> executor.execute(wrongTenant, rejected))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(refundedAmount()).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_ledger WHERE tenant_id = ?", Integer.class,
                tenantId)).isZero();
    }

    @Test
    void overRefundLeavesNoPartialLedgerAndMarksActionFailed() {
        UUID actionId = addAction("APPROVED", 901);

        ExecutionResult result = executor.execute(tenantId, actionId);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("REFUND_AMOUNT_INVALID");
        assertThat(refundedAmount()).isEqualTo(100L);
        assertThat(count("refund_ledger", actionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sensitive_actions WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, actionId)).isEqualTo("FAILED");
        assertThat(countAudit("SENSITIVE_ACTION_EXECUTION_FAILED", actionId)).isEqualTo(1);
    }

    @Test
    void ledgerWriteFailureRollsBackOrderActionAndAuditTogether() {
        UUID actionId = addAction("APPROVED", 200);
        jdbc.execute("""
                CREATE FUNCTION fail_demo_refund_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced refund ledger insert failure'; END
                $$
                """);
        jdbc.execute("CREATE TRIGGER fail_demo_refund_ledger BEFORE INSERT ON refund_ledger "
                + "FOR EACH ROW EXECUTE FUNCTION fail_demo_refund_ledger()");

        try {
            assertThatThrownBy(() -> executor.execute(tenantId, actionId))
                    .isInstanceOf(DataAccessException.class);
            assertThat(refundedAmount()).isEqualTo(100L);
            assertThat(count("refund_ledger", actionId)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM sensitive_actions WHERE tenant_id = ? AND id = ?",
                    String.class, tenantId, actionId)).isEqualTo("APPROVED");
            assertThat(countAudit("REFUND_LOCAL_DEMO_RECORDED", actionId)).isZero();
        }
        finally {
            jdbc.execute("DROP TRIGGER fail_demo_refund_ledger ON refund_ledger");
            jdbc.execute("DROP FUNCTION fail_demo_refund_ledger()");
        }
    }

    private ExecutionResult executeAtStart(CountDownLatch ready, CountDownLatch start, UUID actionId)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return executor.execute(tenantId, actionId);
    }

    private UUID addAction(String status, long amountCents) {
        UUID draftId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        String payload = "{\"orderId\":\"" + orderId + "\",\"amountCents\":" + amountCents
                + ",\"reason\":\"demo test\"}";
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, 'REQUEST_REFUND', CAST(? AS jsonb), ?, '{}'::jsonb, 'CONFIRMED', ?)
                """, draftId, tenantId, requesterId, payload, "f".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO sensitive_actions(id, tenant_id, draft_id, requested_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, ?, ?, 'REFUND_REQUEST', CAST(? AS jsonb), ?, '{}'::jsonb, ?, ?)
                """, actionId, tenantId, draftId, requesterId, payload, "f".repeat(64), status,
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return actionId;
    }

    private long refundedAmount() {
        return jdbc.queryForObject("SELECT refunded_amount_cents FROM orders WHERE tenant_id = ? AND id = ?",
                Long.class, tenantId, orderId);
    }

    private int count(String table, UUID actionId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND action_id = ?",
                Integer.class, tenantId, actionId);
    }

    private int countAudit(String action, UUID actionId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = ? "
                + "AND resource_id = ?", Integer.class, tenantId, action, actionId);
    }
}
