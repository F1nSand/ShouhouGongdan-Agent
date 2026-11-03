package com.aftercare.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.knowledge.worker-enabled=false",
        "app.action-worker.enabled=false",
        "app.knowledge.storage-dir=./target/knowledge-workflow-it"
})
@ActiveProfiles("mock")
class KnowledgeWorkflowIT {
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
    private DocumentService documents;

    @Autowired
    private KnowledgeIngestionWorker worker;

    @Autowired
    private PolicyRetriever retriever;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    private UUID tenantId;
    private UUID customerId;
    private UUID oldOrderId;
    private UUID currentOrderId;
    private ActorContext admin;
    private ActorContext staff;

    @BeforeEach
    void createTenantAndOrders() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        oldOrderId = UUID.randomUUID();
        currentOrderId = UUID.randomUUID();
        String tenantCode = "KNOWLEDGE_" + tenantId.toString().replace("-", "");
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Knowledge test', 'ACTIVE')",
                tenantId, tenantCode);
        jdbc.update("""
                INSERT INTO customers(id, tenant_id, display_name, phone_masked, external_ref)
                VALUES (?, ?, 'Test customer', '138****1000', ?)
                """, customerId, tenantId, tenantCode);
        Instant oldPurchase = Instant.parse("2024-06-01T00:00:00Z");
        Instant currentPurchase = Instant.parse("2025-06-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at,
                    paid_amount_cents, promised_delivery_at)
                VALUES (?, ?, ?, ?, 'PAID', ?, 10000, ?), (?, ?, ?, ?, 'PAID', ?, 10000, ?)
                """, oldOrderId, tenantId, "OLD_" + tenantCode, customerId, Timestamp.from(oldPurchase),
                Timestamp.from(oldPurchase.plusSeconds(86400)), currentOrderId, tenantId,
                "CURRENT_" + tenantCode, customerId, Timestamp.from(currentPurchase),
                Timestamp.from(currentPurchase.plusSeconds(86400)));
        admin = new ActorContext(tenantId, UUID.randomUUID(), Role.ADMIN, "knowledge-test");
        staff = new ActorContext(tenantId, UUID.randomUUID(), Role.CUSTOMER_SERVICE, "knowledge-test");
    }

    @Test
    void retryResetsFailedVersionAndIngestionJobAndRemovesStaleChunks() {
        var uploaded = documents.upload(admin, command("重试政策", "RETURNS",
                Instant.parse("2024-01-01T00:00:00Z"), "retry.txt", "text/plain",
                "可重试的售后政策内容。".getBytes(StandardCharsets.UTF_8)));
        // Parser/embedding errors can fail the version inside ingest(), after which processOne completes the job.
        jdbc.update("UPDATE knowledge_versions SET status = 'FAILED', failure_reason = 'INGESTION_FAILED' WHERE id = ?",
                uploaded.versionId());
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'DONE', attempts = 1, last_error = NULL
                WHERE tenant_id = ? AND aggregate_id = ? AND job_type = 'KNOWLEDGE_INGEST'
                """, tenantId, uploaded.versionId());
        var staleEmbedding = embeddingProvider.embedDocuments(List.of("stale chunk")).getFirst();
        jdbc.update("""
                INSERT INTO knowledge_chunks(id, tenant_id, version_id, chunk_index, content,
                    embedding, embedding_model)
                VALUES (?, ?, ?, 0, 'stale chunk', CAST(? AS vector), ?)
                """, UUID.randomUUID(), tenantId, uploaded.versionId(), vectorLiteral(staleEmbedding),
                embeddingProvider.modelName());

        var retried = documents.retry(admin, uploaded.versionId());

        assertThat(retried.status()).isEqualTo("UPLOADED");
        assertThat(retried.failureReason()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE tenant_id = ? AND version_id = ?",
                Integer.class, tenantId, uploaded.versionId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status || ':' || attempts || ':' || COALESCE(last_error, '') FROM outbox_jobs
                WHERE tenant_id = ? AND aggregate_id = ? AND job_type = 'KNOWLEDGE_INGEST'
                """, String.class, tenantId, uploaded.versionId())).isEqualTo("READY:0:");
        assertThat(worker.processOne()).isTrue();
        assertThat(documents.getVersion(admin, uploaded.versionId()).status()).isEqualTo("READY");
    }

    @Test
    void retryRejectsNonFailedVersionsAndHidesVersionsFromOtherTenants() {
        var uploaded = documents.upload(admin, command("重试校验", "RETURNS",
                Instant.parse("2024-01-01T00:00:00Z"), "retry-check.txt", "text/plain",
                "处理中的政策内容。".getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'DONE', updated_at = now()
                WHERE tenant_id = ? AND aggregate_id = ? AND job_type = 'KNOWLEDGE_INGEST'
                """, tenantId, uploaded.versionId());

        assertThatThrownBy(() -> documents.retry(admin, uploaded.versionId()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("VERSION_NOT_FAILED");

        jdbc.update("UPDATE knowledge_versions SET status = 'FAILED', failure_reason = 'INGESTION_FAILED' WHERE id = ?",
                uploaded.versionId());
        assertThatThrownBy(() -> documents.retry(staff, uploaded.versionId()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("FORBIDDEN");

        UUID otherTenant = UUID.randomUUID();
        String otherCode = "OTHER_" + otherTenant.toString().replace("-", "");
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Other tenant', 'ACTIVE')",
                otherTenant, otherCode);
        var otherAdmin = new ActorContext(otherTenant, UUID.randomUUID(), Role.ADMIN, "knowledge-test");
        assertThatThrownBy(() -> documents.retry(otherAdmin, uploaded.versionId()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void duplicateTextUploadAndRepeatedIngestionCreateOnlyOneChunk() {
        byte[] content = "售后退货政策：支持七日内申请退货。".getBytes(StandardCharsets.UTF_8);
        var uploaded = documents.upload(admin, command("退货政策", "RETURNS",
                Instant.parse("2024-01-01T00:00:00Z"), "returns.txt", "text/plain", content));
        assertThatThrownBy(() -> documents.uploadVersion(admin, uploaded.documentId(), command("错误标题", "RETURNS",
                Instant.parse("2024-01-01T00:00:00Z"), "returns.txt", "text/plain", content)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("INVALID_ARGUMENT");

        assertThat(worker.processOne()).isTrue();
        assertThat(documents.getVersion(admin, uploaded.versionId()).status()).isEqualTo("READY");

        var duplicate = documents.uploadVersion(admin, uploaded.documentId(), command("退货政策", "RETURNS",
                Instant.parse("2024-01-01T00:00:00Z"), "returns.txt", "text/plain", content));
        worker.ingest(tenantId, uploaded.versionId());

        assertThat(duplicate.versionId()).isEqualTo(uploaded.versionId());
        assertThat(duplicate.reusedExistingVersion()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE tenant_id = ? AND version_id = ?",
                Integer.class, tenantId, uploaded.versionId())).isEqualTo(1);
    }

    @Test
    void scannedPdfIsMarkedFailedWithAnActionableReason() throws Exception {
        byte[] pdfBytes;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(output);
            pdfBytes = output.toByteArray();
        }
        var uploaded = documents.upload(admin, command("扫描政策", "RETURNS",
                Instant.parse("2024-01-01T00:00:00Z"), "scanned.pdf", "application/pdf", pdfBytes));

        assertThat(worker.processOne()).isTrue();
        var failed = documents.getVersion(admin, uploaded.versionId());

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("EMPTY_TEXT");
    }

    @Test
    void orderDateSelectsItsPublishedPolicyAndExcludesDraftsAndOtherTenants() {
        String query = "售后退款政策统一检索";
        var policy = uploadAndPublish(admin, "退款政策", "REFUNDS", "2024政策：" + query + "，支持七日内退款。",
                Instant.parse("2024-01-01T00:00:00Z"));
        var revised = documents.uploadVersion(admin, policy.documentId(), command("退款政策", "REFUNDS",
                Instant.parse("2025-01-01T00:00:00Z"), "refunds-v2.txt", "text/plain",
                ("2025政策：" + query + "，支持十四日内退款。").getBytes(StandardCharsets.UTF_8)));
        assertThat(worker.processOne()).isTrue();
        documents.publish(admin, revised.versionId());

        var draft = documents.upload(admin, command("退款政策草稿", "REFUNDS",
                Instant.parse("2025-01-01T00:00:00Z"), "draft.txt", "text/plain",
                ("草稿：" + query + "，尚未发布。").getBytes(StandardCharsets.UTF_8)));
        assertThat(worker.processOne()).isTrue();

        UUID otherTenant = UUID.randomUUID();
        String otherCode = "OTHER_" + otherTenant.toString().replace("-", "");
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Other tenant', 'ACTIVE')",
                otherTenant, otherCode);
        var otherAdmin = new ActorContext(otherTenant, UUID.randomUUID(), Role.ADMIN, "knowledge-test");
        uploadAndPublish(otherAdmin, "他商家政策", "REFUNDS", "他商家政策：" + query + "，其他内容。",
                Instant.parse("2024-01-01T00:00:00Z"));

        assertThat(documents.getVersion(admin, revised.versionId()).status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_chunks kc
                JOIN knowledge_versions kv ON kv.tenant_id = kc.tenant_id AND kv.id = kc.version_id
                WHERE kc.tenant_id = ? AND kv.id = ? AND kv.status = 'PUBLISHED'
                  AND kv.effective_from <= ? AND (kv.effective_to IS NULL OR kv.effective_to > ?)
                """, Integer.class, tenantId, revised.versionId(),
                Timestamp.from(Instant.parse("2025-06-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2025-06-01T00:00:00Z")))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT 1 - (embedding <=> CAST(? AS vector)) FROM knowledge_chunks
                WHERE tenant_id = ? AND version_id = ?
                """, Double.class, vectorLiteral(embeddingProvider.embedQuery(query)),
                tenantId, revised.versionId())).isGreaterThanOrEqualTo(0.60);

        var oldOrderEvidence = retriever.retrieve(staff, query, oldOrderId, "REFUNDS");
        var currentOrderEvidence = retriever.retrieve(staff, query, currentOrderId, "REFUNDS");
        var unfilteredEvidence = retriever.retrieve(staff, query, currentOrderId, null);

        assertThat(oldOrderEvidence.chunks()).hasSize(1);
        assertThat(oldOrderEvidence.chunks().getFirst().versionNo()).isEqualTo(1);
        assertThat(oldOrderEvidence.chunks().getFirst().content()).contains("七日内退款")
                .doesNotContain("十四日内退款", "尚未发布", "他商家");
        assertThat(currentOrderEvidence.chunks()).hasSize(1);
        assertThat(currentOrderEvidence.chunks().getFirst().versionNo()).isEqualTo(2);
        assertThat(currentOrderEvidence.chunks().getFirst().content()).contains("十四日内退款")
                .doesNotContain("七日内退款", "尚未发布", "他商家");
        assertThat(unfilteredEvidence.chunks()).hasSize(1);
        assertThat(unfilteredEvidence.chunks().getFirst().versionNo()).isEqualTo(2);
        assertThat(documents.getVersion(admin, draft.versionId()).status()).isEqualTo("READY");
    }

    private KnowledgeVersionView uploadAndPublish(ActorContext actor, String title, String category,
            String text, Instant effectiveFrom) {
        var uploaded = documents.upload(actor, command(title, category, effectiveFrom, "policy.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8)));
        assertThat(worker.processOne()).isTrue();
        documents.publish(actor, uploaded.versionId());
        return uploaded;
    }

    private static DocumentService.UploadPolicyCommand command(String title, String category, Instant effectiveFrom,
            String fileName, String contentType, byte[] content) {
        return new DocumentService.UploadPolicyCommand(title, category, effectiveFrom, null,
                fileName, contentType, content);
    }

    private static String vectorLiteral(float[] vector) {
        var literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }
}
