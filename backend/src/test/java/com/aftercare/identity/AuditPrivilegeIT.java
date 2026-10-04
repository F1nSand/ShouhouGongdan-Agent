package com.aftercare.identity;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AuditPrivilegeIT {
    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aftercare")
            .withUsername("aftercare_migrator")
            .withPassword("local-migration-password");

    @Test
    void runtimeCannotUpdateAuditHistory() throws Exception {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID tenantId = UUID.randomUUID();
        try (var migrationConnection = DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            var createTenant = migrationConnection.prepareStatement(
                    "INSERT INTO tenants(id, code, name, status) VALUES (?, 'PRIVILEGE_TEST', '测试商家', 'ACTIVE')");
            createTenant.setObject(1, tenantId);
            createTenant.executeUpdate();
            migrationConnection.createStatement().execute("""
                    INSERT INTO audit_events(id, tenant_id, trace_id, action, resource_type, result)
                    VALUES ('a1e21c82-d0df-4f46-bfdc-8d3c6d4a4f9b', '%s', 'audit-test', 'TEST', 'TEST', 'SUCCESS')
                    """.formatted(tenantId));
            migrationConnection.createStatement().execute(
                    "ALTER ROLE aftercare_app LOGIN PASSWORD 'runtime-test-password'");
        }

        assertThatThrownBy(() -> {
            try (var runtimeConnection = DriverManager.getConnection(
                    DATABASE.getJdbcUrl(), "aftercare_app", "runtime-test-password")) {
                runtimeConnection.createStatement().execute(
                        "UPDATE audit_events SET reason = 'tampered' WHERE trace_id = 'audit-test'");
            }
        }).isInstanceOf(PSQLException.class)
                .hasMessageContaining("permission denied for table audit_events");
    }
}
