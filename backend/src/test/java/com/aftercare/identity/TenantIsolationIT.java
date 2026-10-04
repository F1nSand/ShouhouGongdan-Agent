package com.aftercare.identity;

import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIT {
    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aftercare")
            .withUsername("aftercare_migrator")
            .withPassword("local-migration-password");

    @Test
    void migrationsCreateTenantScopedRelationsFromAnEmptyDatabase() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DATABASE.createConnection("")) {
            var tables = connection.createStatement().executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'");
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isGreaterThanOrEqualTo(30);
            var dimension = connection.createStatement().executeQuery(
                    "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                            + "WHERE attrelid='knowledge_chunks'::regclass AND attname='embedding'");
            assertThat(dimension.next()).isTrue();
            assertThat(dimension.getString(1)).isEqualTo("vector(1024)");
        }

        UUID tenantA = UUID.fromString("4f8fb896-021e-44ae-9da9-6c8c77db8132");
        UUID tenantB = UUID.fromString("7ddf1f4a-c55d-4a48-b6b2-2765a34d56bb");
        UUID customerB = UUID.fromString("bd955108-e8ab-4f05-8b23-03f2b6bc5ceb");
        try (var connection = DATABASE.createConnection("")) {
            var insertTenant = connection.prepareStatement(
                    "INSERT INTO tenants(id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')");
            insertTenant.setObject(1, tenantA);
            insertTenant.setString(2, "TENANT_A");
            insertTenant.setString(3, "商家 A");
            insertTenant.executeUpdate();
            insertTenant.setObject(1, tenantB);
            insertTenant.setString(2, "TENANT_B");
            insertTenant.setString(3, "商家 B");
            insertTenant.executeUpdate();
            var customer = connection.prepareStatement(
                    "INSERT INTO customers(id, tenant_id, display_name, external_ref) VALUES (?, ?, '演示客户', 'B-1')");
            customer.setObject(1, customerB);
            customer.setObject(2, tenantB);
            customer.executeUpdate();
            var order = connection.prepareStatement("""
                    INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at,
                        paid_amount_cents, promised_delivery_at)
                    VALUES (?, ?, 'A-1', ?, 'PAID', now(), 1000, now())
                    """);
            order.setObject(1, UUID.randomUUID());
            order.setObject(2, tenantA);
            order.setObject(3, customerB);
            try {
                order.executeUpdate();
                throw new AssertionError("Cross-tenant customer reference unexpectedly succeeded");
            }
            catch (SQLException rejected) {
                assertThat(rejected.getSQLState()).isEqualTo("23503");
            }
        }
    }
}
