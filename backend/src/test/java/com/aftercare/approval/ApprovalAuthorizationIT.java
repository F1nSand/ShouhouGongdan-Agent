package com.aftercare.approval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.action-worker.enabled=false", "app.knowledge.worker-enabled=false"})
@ActiveProfiles("mock")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalAuthorizationIT {
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
    private ApprovalService approvals;

    private UUID tenantId;
    private UUID requesterId;
    private UUID csrId;
    private UUID supervisorId;
    private UUID adminId;
    private ActorContext requester;
    private ActorContext csr;
    private ActorContext supervisor;
    private ActorContext admin;

    @BeforeEach
    void insertTenantAndActors() {
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Approval test', 'ACTIVE')",
                tenantId, "APPROVAL_" + tenantId.toString().replace("-", ""));
        requesterId = addUser("requester", Role.CUSTOMER_SERVICE);
        csrId = addUser("csr", Role.CUSTOMER_SERVICE);
        supervisorId = addUser("supervisor", Role.SERVICE_SUPERVISOR);
        adminId = addUser("admin", Role.ADMIN);
        requester = actor(requesterId, Role.CUSTOMER_SERVICE);
        csr = actor(csrId, Role.CUSTOMER_SERVICE);
        supervisor = actor(supervisorId, Role.SERVICE_SUPERVISOR);
        admin = actor(adminId, Role.ADMIN);
    }

    @Test
    void csrCannotApproveAndRequesterCannotApproveOwnAction() {
        UUID csrAction = createAction(requesterId, "REFUND_REQUEST");
        assertThatThrownBy(() -> approvals.decide(csr, csrAction, Decision.APPROVE,
                "approve", 0, "csr-approval-1"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("FORBIDDEN");

        UUID selfRequestedAction = createAction(supervisorId, "REFUND_REQUEST");
        assertThatThrownBy(() -> approvals.decide(supervisor, selfRequestedAction, Decision.APPROVE,
                "approve own request", 0, "self-approval-1"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    void concurrentDecisionsHaveOneWinner() throws Exception {
        UUID actionId = createAction(requesterId, "REFUND_REQUEST");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            results.add(executor.submit(() -> decideAtStart(ready, start, supervisor, actionId,
                    Decision.APPROVE, "decision-approve")));
            results.add(executor.submit(() -> decideAtStart(ready, start, admin, actionId,
                    Decision.REJECT, "decision-reject")));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (var result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream().filter(value -> value.startsWith("WON:")).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(value -> "VERSION_CONFLICT".equals(value)
                    || "APPROVAL_ALREADY_DECIDED".equals(value)).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions WHERE tenant_id = ? AND action_id = ?",
                Integer.class, tenantId, actionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_jobs WHERE tenant_id = ? AND aggregate_id = ?",
                Integer.class, tenantId, actionId)).isIn(0, 1);
    }

    private String decideAtStart(CountDownLatch ready, CountDownLatch start, ActorContext actor,
            UUID actionId, Decision decision, String key) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            return "WON:" + approvals.decide(actor, actionId, decision, "concurrent decision", 0, key).status();
        }
        catch (ApiException failure) {
            return failure.code();
        }
    }

    private UUID createAction(UUID requestedBy, String type) {
        UUID draftId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        String draftType = "REFUND_REQUEST".equals(type) ? "REQUEST_REFUND" : "REQUEST_STOCK_TRANSFER";
        String payload = "REFUND_REQUEST".equals(type) ? "{\"orderId\":\"" + UUID.randomUUID()
                + "\",\"amountCents\":100,\"reason\":\"test\"}" : "{}";
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, ?, CAST(? AS jsonb), ?, '{}'::jsonb, 'CONFIRMED', ?)
                """, draftId, tenantId, requestedBy, draftType, payload, "a".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO sensitive_actions(id, tenant_id, draft_id, requested_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, '{}'::jsonb, 'PENDING_APPROVAL', ?)
                """, actionId, tenantId, draftId, requestedBy, type, payload, "a".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return actionId;
    }

    private UUID addUser(String name, Role role) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', ?, 'ACTIVE')
                """, userId, tenantId, name + "_" + userId.toString().substring(0, 8), role.name());
        return userId;
    }

    private ActorContext actor(UUID id, Role role) {
        return new ActorContext(tenantId, id, role, "approval-test");
    }
}
