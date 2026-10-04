package com.aftercare.order;

import java.time.Instant;
import java.util.UUID;
import java.sql.Timestamp;

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
import org.springframework.http.MediaType;
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
class OrderTenantIsolationIT {
    private static final String JWT_SECRET = "bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==";
    private static final UUID TENANT_A = UUID.fromString("4f8fb896-021e-44ae-9da9-6c8c77db8132");
    private static final UUID TENANT_B = UUID.fromString("7ddf1f4a-c55d-4a48-b6b2-2765a34d56bb");
    private static final UUID CUSTOMER_A = UUID.fromString("bd955108-e8ab-4f05-8b23-03f2b6bc5ceb");
    private static final UUID CUSTOMER_B = UUID.fromString("bd955108-e8ab-4f05-8b23-03f2b6bc5cec");
    private static final UUID ORDER_A = UUID.fromString("ad644231-cf17-4f07-a9ac-ff256a3d8810");
    private static final UUID ORDER_B = UUID.fromString("ad644231-cf17-4f07-a9ac-ff256a3d8811");

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
    private TestRestTemplate http;

    @Autowired
    private AccessTokenService accessTokenService;

    @LocalServerPort
    private int port;

    @BeforeEach
    void insertTwoTenantFixture() {
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", TENANT_A, TENANT_B);
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, 'TENANT_A', '商家 A', 'ACTIVE')",
                TENANT_A);
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, 'TENANT_B', '商家 B', 'ACTIVE')",
                TENANT_B);
        jdbc.update("""
                INSERT INTO customers(id, tenant_id, display_name, phone_masked, external_ref)
                VALUES (?, ?, '测试客户 A', '138****0001', 'CUS-A'), (?, ?, '测试客户 B', '138****0002', 'CUS-B')
                """, CUSTOMER_A, TENANT_A, CUSTOMER_B, TENANT_B);
        Instant purchasedAt = Instant.parse("2026-09-20T00:00:00Z");
        jdbc.update("""
                INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at,
                    paid_amount_cents, promised_delivery_at)
                VALUES (?, ?, 'SAME-ORDER-NO', ?, 'PAID', ?, 1000, ?),
                       (?, ?, 'SAME-ORDER-NO', ?, 'PAID', ?, 1000, ?)
                """, ORDER_A, TENANT_A, CUSTOMER_A, Timestamp.from(purchasedAt),
                Timestamp.from(purchasedAt.plusSeconds(86400)), ORDER_B, TENANT_B, CUSTOMER_B,
                Timestamp.from(purchasedAt), Timestamp.from(purchasedAt.plusSeconds(86400)));
    }

    @Test
    void anotherTenantOrderIsNotFoundByIdOrOrderNumber() {
        String token = accessTokenService.issue(new RegisteredAccount(TENANT_A, "TENANT_A", UUID.randomUUID(),
                "csr", "unused", Role.CUSTOMER_SERVICE, "ACTIVE", "ACTIVE")).value();
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        var entity = new HttpEntity<Void>(headers);

        var ownOrder = http.exchange(url("/api/v1/orders/" + ORDER_A), HttpMethod.GET, entity, String.class);
        var otherOrder = http.exchange(url("/api/v1/orders/" + ORDER_B), HttpMethod.GET, entity, String.class);
        var otherOrderNumber = http.exchange(url("/api/v1/orders?orderNo=SAME-ORDER-NO&tenant=TENANT_B"),
                HttpMethod.GET, entity, String.class);

        assertThat(ownOrder.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownOrder.getBody()).contains("138****0001");
        assertThat(otherOrder.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherOrder.getBody()).doesNotContain("测试客户 B", "138****0002");
        assertThat(otherOrderNumber.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherOrderNumber.getBody()).contains("测试客户 A", "138****0001").doesNotContain("138****0002");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
