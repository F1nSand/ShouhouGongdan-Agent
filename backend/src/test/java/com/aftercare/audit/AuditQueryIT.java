package com.aftercare.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.aftercare.identity.AccessTokenService;
import com.aftercare.identity.AuthRepository.RegisteredAccount;
import com.aftercare.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.knowledge.worker-enabled=false", "app.action-worker.enabled=false"})
@ActiveProfiles("mock")
class AuditQueryIT {
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
    @Autowired private TestRestTemplate http;
    @Autowired private AccessTokenService tokens;

    @LocalServerPort
    private int port;

    private UUID tenantA;
    private UUID tenantB;
    private UUID resourceId;
    private String adminToken;
    private String customerServiceToken;

    @BeforeEach
    void insertAuditFixtures() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        resourceId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Audit A', 'ACTIVE')",
                tenantA, "AUDIT_A_" + tenantA.toString().replace("-", ""));
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Audit B', 'ACTIVE')",
                tenantB, "AUDIT_B_" + tenantB.toString().replace("-", ""));
        Instant first = Instant.parse("2026-09-25T10:00:00Z");
        insertEvent(tenantA, "trace-a", "ACTION_CONFIRMED", resourceId, first);
        insertEvent(tenantA, "trace-a", "APPROVAL_DECIDED", resourceId, first.plusSeconds(60));
        insertEvent(tenantA, "trace-b", "OTHER_ACTION", UUID.randomUUID(), first.plusSeconds(120));
        insertEvent(tenantB, "trace-a", "FOREIGN_ACTION", resourceId, first.plusSeconds(180));

        adminToken = token(tenantA, "AUDIT_A", Role.ADMIN);
        customerServiceToken = token(tenantA, "AUDIT_A", Role.CUSTOMER_SERVICE);
    }

    @Test
    void filtersArePagedAndScopedToTheAuthenticatedTenant() {
        var result = get("/api/v1/audit-events?traceId=trace-a&resourceId=" + resourceId + "&page=1&size=1",
                adminToken);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).contains("APPROVAL_DECIDED", "\"total\":2")
                .doesNotContain("ACTION_CONFIRMED", "FOREIGN_ACTION", "OTHER_ACTION");

        var next = get("/api/v1/audit-events?traceId=trace-a&resourceId=" + resourceId + "&page=2&size=1",
                adminToken);
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(next.getBody()).contains("ACTION_CONFIRMED", "\"total\":2")
                .doesNotContain("APPROVAL_DECIDED", "FOREIGN_ACTION");
    }

    @Test
    void auditQueryRequiresAdministratorRole() {
        var result = get("/api/v1/audit-events", customerServiceToken);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void insertEvent(UUID tenantId, String trace, String action, UUID resource, Instant createdAt) {
        jdbc.update("""
                INSERT INTO audit_events(id, tenant_id, trace_id, action, resource_type, resource_id,
                    result, parameter_summary, created_at)
                VALUES (?, ?, ?, ?, 'TEST', ?, 'SUCCESS', '{"scope":"test"}'::jsonb, ?)
                """, UUID.randomUUID(), tenantId, trace, action, resource, Timestamp.from(createdAt));
    }

    private String token(UUID tenantId, String code, Role role) {
        return tokens.issue(new RegisteredAccount(tenantId, code, UUID.randomUUID(), "user", "unused",
                role, "ACTIVE", "ACTIVE")).value();
    }

    private org.springframework.http.ResponseEntity<String> get(String path, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<Void>(headers), String.class);
    }
}
