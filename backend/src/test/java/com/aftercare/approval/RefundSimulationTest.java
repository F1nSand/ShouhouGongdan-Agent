package com.aftercare.approval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.action-worker.enabled=false", "app.knowledge.worker-enabled=false"})
@ActiveProfiles("mock")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundSimulationTest {
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

    private UUID tenantId;
    private UUID requesterId;
    private UUID orderId;

    @BeforeEach
    void insertOrderFixture() {
        tenantId = UUID.randomUUID();
        requesterId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Refund test', 'ACTIVE')",
                tenantId, "REFUND_" + tenantId.toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', ?, 'ACTIVE')
                """, requesterId, tenantId, "requester_" + requesterId.toString().substring(0, 8),
                Role.CUSTOMER_SERVICE.name());
        jdbc.update("""
                INSERT INTO customers(id, tenant_id, display_name, phone_masked, external_ref)
                VALUES (?, ?, 'Test customer', '***', ?)
                """, customerId, tenantId, "customer-" + customerId);
        jdbc.update("""
                INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at,
                    paid_amount_cents, refunded_amount_cents, promised_delivery_at)
                VALUES (?, ?, ?, ?, 'PAID', ?, 1000, 100, ?)
                """, orderId, tenantId, "ORDER-" + orderId, customerId,
                Timestamp.from(Instant.now().minusSeconds(86400)), Timestamp.from(Instant.now().plusSeconds(86400)));
    }

    @Test
    void noPaymentMutation() {
        String payload = "{\"orderId\":\"" + orderId + "\",\"amountCents\":200,\"reason\":\"late delivery\"}";
        UUID draftId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, 'REQUEST_REFUND', CAST(? AS jsonb), ?, '{}'::jsonb, 'CONFIRMED', ?)
                """, draftId, tenantId, requesterId, payload, "d".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO sensitive_actions(id, tenant_id, draft_id, requested_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, ?, ?, 'REFUND_REQUEST', CAST(? AS jsonb), ?, '{}'::jsonb, 'APPROVED', ?)
                """, actionId, tenantId, draftId, requesterId, payload, "d".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));

        ExecutionResult result = actionExecutor.execute(tenantId, actionId);

        assertThat(result.status()).isEqualTo("SIMULATED_SUCCEEDED");
        assertThat(result.simulation()).isTrue();
        assertThat(jdbc.queryForObject("SELECT paid_amount_cents FROM orders WHERE tenant_id = ? AND id = ?",
                Long.class, tenantId, orderId)).isEqualTo(1000L);
        assertThat(jdbc.queryForObject("SELECT refunded_amount_cents FROM orders WHERE tenant_id = ? AND id = ?",
                Long.class, tenantId, orderId)).isEqualTo(100L);
    }
}
