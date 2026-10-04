package com.aftercare.demo;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
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

import com.aftercare.identity.AccessTokenService;
import com.aftercare.identity.AuthRepository.RegisteredAccount;
import com.aftercare.identity.Role;
import com.aftercare.inventory.InventoryController.RecommendationRequest;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.demo-seed.enabled=true", "app.knowledge.worker-enabled=false",
                "app.action-worker.enabled=false", "DEMO_CSR_PASSWORD=test-csr",
                "DEMO_SUPERVISOR_PASSWORD=test-supervisor", "DEMO_WAREHOUSE_PASSWORD=test-warehouse",
                "DEMO_ADMIN_PASSWORD=test-admin"})
@ActiveProfiles("mock")
class DemoSeedIT {
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
    private DemoSeedService demoSeedService;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AccessTokenService accessTokenService;

    @LocalServerPort
    private int port;

    @Test
    void seedCreatesTheDocumentedFixtureAndCanBeRepeated() {
        assertFixtureCounts();

        demoSeedService.run(new DefaultApplicationArguments(new String[0]));

        assertFixtureCounts();

        UUID tenantId = seededId("tenant:DEMO_MERCHANT");
        String token = accessTokenService.issue(new RegisteredAccount(tenantId, "DEMO_MERCHANT",
                seededId("user:" + tenantId + ":csr"), "csr", "unused", Role.CUSTOMER_SERVICE,
                "ACTIVE", "ACTIVE")).value();
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var inventory = http.exchange(url("/api/v1/inventory?skuCode=SKU-001"), HttpMethod.GET,
                new HttpEntity<Void>(headers), String.class);
        assertThat(inventory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inventory.getBody()).contains("SKU-001", "WH-EAST", "WH-CENTRAL", "WH-SOUTH");

        var recommendation = http.exchange(url("/api/v1/inventory/recommendations"), HttpMethod.POST,
                new HttpEntity<>(new RecommendationRequest(seededId("sku:" + tenantId + ":SKU-001"),
                        seededId("warehouse:" + tenantId + ":WH-SOUTH"), 1), headers), String.class);
        assertThat(recommendation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recommendation.getBody()).contains("estimatedDays", "costCentsPerUnit",
                seededId("warehouse:" + tenantId + ":WH-EAST").toString(),
                seededId("warehouse:" + tenantId + ":WH-CENTRAL").toString());
    }

    private void assertFixtureCounts() {
        assertThat(count("SELECT count(*) FROM tenants")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = "
                + "(SELECT id FROM tenants WHERE code = 'DEMO_MERCHANT')")).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM skus WHERE tenant_id = "
                + "(SELECT id FROM tenants WHERE code = 'DEMO_MERCHANT')")).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM warehouses WHERE tenant_id = "
                + "(SELECT id FROM tenants WHERE code = 'DEMO_MERCHANT')")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM inventory WHERE tenant_id = "
                + "(SELECT id FROM tenants WHERE code = 'DEMO_MERCHANT')")).isEqualTo(60);
    }

    private int count(String query) {
        return jdbc.queryForObject(query, Integer.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private UUID seededId(String key) {
        return UUID.nameUUIDFromBytes(("aftercare-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
