package com.aftercare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyConfigTest {

    @Test
    void embeddingDimensionRejectsMismatch() {
        new WebApplicationContextRunner()
                .withUserConfiguration(AftersalesApplication.class)
                .withPropertyValues(
                        "app.embedding.dimensions=1536",
                        "app.security.jwt-secret=bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==",
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/aftercare",
                        "spring.datasource.username=aftercare_app",
                        "spring.datasource.password=local-dev-only",
                        "spring.flyway.enabled=false",
                        "spring.profiles.active=mock",
                        "app.knowledge.worker-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("app.embedding.dimensions must be 1024, but was 1536");
                });
    }
}
