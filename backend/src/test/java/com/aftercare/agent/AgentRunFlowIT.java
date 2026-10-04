package com.aftercare.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.aftercare.approval.ActionDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.knowledge.worker-enabled=false", "app.action-worker.enabled=false"})
@ActiveProfiles("mock")
class AgentRunFlowIT {
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
    private ConversationService conversations;

    @Autowired
    private AgentRunService runs;

    @Autowired
    private RunEventService events;

    @Autowired
    private ActionDraftService drafts;

    private UUID tenantId;
    private UUID actorId;
    private UUID orderId;
    private ActorContext actor;

    @BeforeEach
    void prepareOrder() {
        tenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String tenantCode = "FLOW_" + tenantId.toString().replace("-", "");
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Agent flow', 'ACTIVE')",
                tenantId, tenantCode);
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', 'CUSTOMER_SERVICE', 'ACTIVE')
                """, actorId, tenantId, "csr_" + actorId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO customers(id, tenant_id, display_name, phone_masked, external_ref)
                VALUES (?, ?, 'Demo customer', '138****0000', ?)
                """, customerId, tenantId, "cus_" + customerId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at, paid_amount_cents,
                    promised_delivery_at)
                VALUES (?, ?, 'AF202609270001', ?, 'PAID', ?, 12800, ?)
                """, orderId, tenantId, customerId, java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),
                java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
        actor = new ActorContext(tenantId, actorId, Role.CUSTOMER_SERVICE, "agent-flow");
    }

    @Test
    void mockOrderQueryRunsThroughApiServicesAndPersistsFinalAnswer() {
        var conversation = conversations.create(actor, "Agent flow test");
        var started = runs.start(actor, conversation.conversationId(), UUID.randomUUID(),
                "请查询订单 AF202609270001");

        var replay = events.events(actor, started.runId(), 0).map(ServerSentEvent::data)
                .collectList().block(Duration.ofSeconds(20));

        assertThat(replay).isNotNull();
        assertThat(replay).extracting(VisibleRunEvent::eventType)
                .contains("run.started", "tool.started", "tool.completed", "answer.final", "run.completed");
        var finalRun = runs.get(actor, started.runId());
        assertThat(finalRun.status()).isEqualTo("COMPLETED");
        assertThat(finalRun.structuredAnswer().path("answer").asText()).contains("AF202609270001", "PAID");
        assertThat(conversations.messages(actor, conversation.conversationId(), 20))
                .anySatisfy(message -> assertThat(message.role()).isEqualTo("ASSISTANT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_invocations WHERE run_id = ?", Integer.class,
                started.runId())).isEqualTo(1);
    }

    @Test
    void confirmedAgentTicketMovesRunToWaitingAndPublishesActionResult() {
        var conversation = conversations.create(actor, "Agent ticket test");
        var started = runs.start(actor, conversation.conversationId(), UUID.randomUUID(),
                "请帮我创建售后工单，包裹一直没有收到");
        var initialEvents = events.events(actor, started.runId(), 0).map(ServerSentEvent::data)
                .collectList().block(Duration.ofSeconds(20));

        assertThat(runs.get(actor, started.runId()).status()).isEqualTo("WAITING_CONFIRMATION");
        var finalEvent = initialEvents.stream().filter(event -> "answer.final".equals(event.eventType()))
                .findFirst().orElseThrow();
        UUID draftId = UUID.fromString(finalEvent.payload().path("draftIds").get(0).asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class)).isZero();

        var confirmed = drafts.confirm(actor, draftId, "agent-flow-confirm");
        long lastSequence = initialEvents.getLast().sequence();
        var postConfirmationEvents = events.events(actor, started.runId(), lastSequence)
                .map(ServerSentEvent::data).collectList().block(Duration.ofSeconds(5));

        assertThat(confirmed.ticketId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE id = ?", Integer.class,
                confirmed.ticketId())).isEqualTo(1);
        assertThat(postConfirmationEvents).extracting(VisibleRunEvent::eventType).contains("action.result");
        assertThat(conversations.messages(actor, conversation.conversationId(), 20))
                .anySatisfy(message -> assertThat(message.content()).contains("已创建"));
    }
}
