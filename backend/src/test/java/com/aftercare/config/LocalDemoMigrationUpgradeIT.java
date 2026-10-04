package com.aftercare.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LocalDemoMigrationUpgradeIT {
    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("migration_upgrade")
            .withUsername("aftercare_migrator")
            .withPassword("local-migration-password");

    @Test
    void upgradesVersionFiveWithoutResettingTheLifetimeBudget() {
        var dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();

        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE model_request_budget SET attempts = 7 WHERE singleton_id = 1");

        var result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempts FROM model_request_budget WHERE singleton_id = 1", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_ledger", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_request_budget_daily", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT has_table_privilege('aftercare_app', 'model_request_budget_daily', 'SELECT, INSERT, UPDATE')
                """, Boolean.class)).isTrue();
    }
}
