package com.aftercare.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.knowledge.worker-enabled=false", "app.action-worker.enabled=false"})
@ActiveProfiles("mock")
class SseReplayIT {
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
    private RunEventService events;

    private UUID tenantId;
    private UUID ownerId;
    private UUID otherUserId;
    private UUID conversationId;
    private UUID runId;
    private ActorContext owner;
    private ActorContext otherUser;

    @BeforeEach
    void prepareRun() {
        tenantId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        runId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'SSE test', 'ACTIVE')",
                tenantId, "SSE_" + tenantId.toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', 'CUSTOMER_SERVICE', 'ACTIVE'),
                       (?, ?, ?, 'unused', 'CUSTOMER_SERVICE', 'ACTIVE')
                """, ownerId, tenantId, "owner_" + ownerId.toString().substring(0, 8),
                otherUserId, tenantId, "other_" + otherUserId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO conversations(id, tenant_id, owner_user_id, title, status)
                VALUES (?, ?, ?, 'SSE test', 'ACTIVE')
                """, conversationId, tenantId, ownerId);
        jdbc.update("""
                INSERT INTO agent_runs(id, tenant_id, conversation_id, actor_id, request_id, input_hash,
                    status, deadline_at, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, 'WAITING_CONFIRMATION', ?, 'sse-test')
                """, runId, tenantId, conversationId, ownerId, requestId, "a".repeat(64),
                java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
        owner = new ActorContext(tenantId, ownerId, Role.CUSTOMER_SERVICE, "sse-owner");
        otherUser = new ActorContext(tenantId, otherUserId, Role.CUSTOMER_SERVICE, "sse-other");
    }

    @Test
    void reconnectGetsOnlyLaterEventsAndEnforcesConversationOwner() {
        for (int sequence = 1; sequence <= 12; sequence++) {
            events.appendSystem(tenantId, runId, "tool.completed", java.util.Map.of("index", sequence));
        }
        events.appendSystem(tenantId, runId, "answer.delta", java.util.Map.of("text", "已校验答复"));
        events.appendSystem(tenantId, runId, "answer.final", java.util.Map.of("answer", "已校验答复"));

        var replayed = events.events(owner, runId, 12).map(ServerSentEvent::data)
                .collectList().block(Duration.ofSeconds(5));

        assertThat(replayed).extracting(VisibleRunEvent::sequence).containsExactly(13L, 14L);
        assertThatThrownBy(() -> events.events(otherUser, runId, 12))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> events.events(owner, runId, 15))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("INVALID_EVENT_CURSOR");
    }
}
