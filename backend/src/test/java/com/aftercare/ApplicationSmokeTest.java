package com.aftercare;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "app.knowledge.worker-enabled=false",
                "app.action-worker.enabled=false", "app.agent.recovery-enabled=false"})
@ActiveProfiles("mock")
class ApplicationSmokeTest {

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    @Test
    void mockStartsWithoutModelKeyAndLivenessIsUp() {
        var response = http.getForEntity("http://localhost:" + port + "/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
