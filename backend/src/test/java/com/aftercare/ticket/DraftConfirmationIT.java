package com.aftercare.ticket;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aftercare.approval.ActionDraftService;
import com.aftercare.approval.ConfirmedActionResult;
import com.aftercare.approval.DraftView;
import com.aftercare.approval.ManualTicketDraftCommand;
import com.aftercare.common.ApiException;
import com.aftercare.identity.AccessTokenService;
import com.aftercare.identity.ActorContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.knowledge.worker-enabled=false", "app.action-worker.enabled=false"})
@ActiveProfiles("mock")
class DraftConfirmationIT {
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
    private ActionDraftService drafts;

    @Autowired
    private TicketService tickets;

    @Autowired
    private AccessTokenService tokenService;

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    private UUID tenantId;
    private UUID userId;
    private String tenantCode;
    private String username;
    private ActorContext actor;

    @BeforeEach
    void insertTenantAndCustomerService() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        tenantCode = "TICKET_" + tenantId.toString().replace("-", "");
        username = "csr_" + tenantId.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Ticket test', 'ACTIVE')",
                tenantId, tenantCode);
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', 'CUSTOMER_SERVICE', 'ACTIVE')
                """, userId, tenantId, username);
        actor = new ActorContext(tenantId, userId, Role.CUSTOMER_SERVICE, "ticket-test");
    }

    @Test
    void repeatedConfirmReturnsSameTicketForTenConcurrentRequests() throws Exception {
        var draft = createDraft("Concurrent ticket", "create-draft-1");
        int requestCount = 10;
        var ready = new CountDownLatch(requestCount);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            var results = new ArrayList<java.util.concurrent.Future<ConfirmedActionResult>>();
            for (int index = 0; index < requestCount; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return drafts.confirm(actor, draft.id(), "confirm-concurrent-1");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ConfirmedActionResult> confirmed = new ArrayList<>();
            for (var result : results) {
                confirmed.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(confirmed).extracting(ConfirmedActionResult::ticketId)
                    .containsOnly(confirmed.getFirst().ticketId());
        }

        assertThat(countTickets()).isEqualTo(1);
        assertThat(drafts.get(actor, draft.id()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void changedIdempotentInputIsConflict() {
        var firstDraft = createDraft("First ticket", "create-draft-2");
        var secondDraft = createDraft("Second ticket", "create-draft-3");

        drafts.confirm(actor, firstDraft.id(), "confirm-reused-key");

        assertThatThrownBy(() -> drafts.confirm(actor, secondDraft.id(), "confirm-reused-key"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(countTickets()).isEqualTo(1);
    }

    @Test
    void confirmIgnoresClientPayloadOverride() {
        var draft = createDraft("Server owned title", "create-draft-4");
        var headers = new HttpHeaders();
        headers.setBearerAuth(issueToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "confirm-payload-override");
        var body = Map.of("title", "Client override", "description", "Forged payload",
                "priority", "URGENT", "amountCents", 1);

        var response = http.exchange(url("/api/v1/action-drafts/" + draft.id() + "/confirm"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var ticket = tickets.get(actor, UUID.fromString(extractTicketId(response.getBody())));
        assertThat(ticket.title()).isEqualTo("Server owned title");
        assertThat(ticket.description()).isEqualTo("The server checked this issue.");
        assertThat(ticket.priority()).isEqualTo("NORMAL");
    }

    @Test
    void manualDraftWorksWithoutAgentRun() {
        var draft = createDraft("Manual ticket", "create-draft-5");

        var result = drafts.confirm(actor, draft.id(), "confirm-manual-1");
        var ticket = tickets.get(actor, result.ticketId());

        assertThat(draft.origin()).isEqualTo("MANUAL");
        assertThat(draft.runId()).isNull();
        assertThat(ticket.sourceRunId()).isNull();
        assertThat(ticket.status()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void assignmentStatusCannotBeSetWithoutAnAssignee() {
        var draft = createDraft("Assignment guard", "create-draft-6");
        var result = drafts.confirm(actor, draft.id(), "confirm-assignment-guard");

        assertThatThrownBy(() -> tickets.transition(actor, result.ticketId(), TicketStatus.ASSIGNED,
                null, 0))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("ASSIGNMENT_REQUIRED");
        var ticket = tickets.get(actor, result.ticketId());
        assertThat(ticket.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.assignedTo()).isNull();
    }

    @Test
    void preparingAgentDraftCannotBeConfirmed() {
        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO conversations(id, tenant_id, owner_user_id, title, status)
                VALUES (?, ?, ?, 'Agent test', 'ACTIVE')
                """, conversationId, tenantId, userId);
        jdbc.update("""
                INSERT INTO agent_runs(id, tenant_id, conversation_id, actor_id, request_id, input_hash,
                    status, deadline_at, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?, 'ticket-test')
                """, runId, tenantId, conversationId, userId, UUID.randomUUID(), "0".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(600)));
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, run_id, invocation_id, conversation_id,
                    created_by, type, payload, payload_hash, evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'AGENT', ?, 'tool-call-1', ?, ?, 'CREATE_TICKET',
                    CAST(? AS jsonb), ?, '{}'::jsonb, 'PREPARING', ?)
                """, draftId, tenantId, runId, conversationId, userId,
                "{\"category\":\"DELIVERY\",\"title\":\"Preparing\",\"description\":\"Wait\",\"priority\":\"NORMAL\"}",
                "1".repeat(64), Timestamp.from(Instant.now().plusSeconds(600)));

        assertThatThrownBy(() -> drafts.confirm(actor, draftId, "confirm-preparing-1"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("DRAFT_NOT_READY");
        assertThat(countTickets()).isZero();
    }

    @Test
    void confirmingTransferDraftCreatesOnlyPendingApproval() {
        UUID skuId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO skus(id, tenant_id, sku_code, name, category_code, status)
                VALUES (?, ?, 'SKU-DRAFT', 'Draft item', 'GENERAL', 'ACTIVE')
                """, skuId, tenantId);
        jdbc.update("""
                INSERT INTO warehouses(id, tenant_id, code, name, region_code, status)
                VALUES (?, ?, 'WH-DRAFT-S', 'Source', 'EAST', 'ACTIVE'),
                       (?, ?, 'WH-DRAFT-T', 'Target', 'WEST', 'ACTIVE')
                """, sourceId, tenantId, targetId, tenantId);
        jdbc.update("""
                INSERT INTO warehouse_routes(id, tenant_id, source_warehouse_id, target_warehouse_id,
                    estimated_days, cost_cents_per_unit)
                VALUES (?, ?, ?, ?, 2, 100)
                """, UUID.randomUUID(), tenantId, sourceId, targetId);
        jdbc.update("""
                INSERT INTO inventory(id, tenant_id, warehouse_id, sku_id, on_hand, reserved, safety_stock)
                VALUES (?, ?, ?, ?, 5, 0, 1), (?, ?, ?, ?, 0, 0, 0)
                """, UUID.randomUUID(), tenantId, sourceId, skuId,
                UUID.randomUUID(), tenantId, targetId, skuId);

        UUID draftId = UUID.randomUUID();
        String payload = "{\"skuId\":\"" + skuId + "\",\"sourceWarehouseId\":\"" + sourceId
                + "\",\"targetWarehouseId\":\"" + targetId + "\",\"quantity\":1}";
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, 'REQUEST_STOCK_TRANSFER', CAST(? AS jsonb), ?, '{}'::jsonb, 'READY', ?)
                """, draftId, tenantId, userId, payload, "e".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(1800)));

        var confirmation = drafts.confirm(actor, draftId, "confirm-transfer-draft");

        assertThat(confirmation.actionId()).isNotNull();
        assertThat(confirmation.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT status FROM sensitive_actions WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, confirmation.actionId())).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE tenant_id = ? AND warehouse_id = ?",
                Integer.class, tenantId, sourceId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_jobs WHERE tenant_id = ? AND aggregate_id = ?",
                Integer.class, tenantId, confirmation.actionId())).isZero();
    }

    private DraftView createDraft(String title, String idempotencyKey) {
        return drafts.createManualDraft(actor, new ManualTicketDraftCommand(null, "DELIVERY", title,
                "The server checked this issue.", "NORMAL"), idempotencyKey);
    }

    private int countTickets() {
        return jdbc.queryForObject("SELECT count(*) FROM tickets WHERE tenant_id = ?", Integer.class, tenantId);
    }

    private String issueToken() {
        return tokenService.issue(new RegisteredAccount(tenantId, tenantCode, userId, username,
                "unused", Role.CUSTOMER_SERVICE, "ACTIVE", "ACTIVE")).value();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String extractTicketId(String response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                    .path("data").path("ticketId").asText();
        }
        catch (Exception invalidJson) {
            throw new AssertionError("Response did not contain a ticket ID", invalidJson);
        }
    }
}
