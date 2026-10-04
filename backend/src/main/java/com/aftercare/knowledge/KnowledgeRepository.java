package com.aftercare.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDocument(UUID tenantId, UUID documentId, String title, String categoryCode) {
        jdbc.update("""
                INSERT INTO knowledge_documents(id, tenant_id, title, category_code, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, documentId, tenantId, title, categoryCode);
    }

    public Optional<KnowledgeVersionView> findExistingVersion(UUID tenantId, UUID documentId, String contentHash) {
        return jdbc.query("""
                SELECT kv.document_id, kv.id AS version_id, kd.title, kd.category_code, kv.version_no,
                    kv.status, kv.effective_from, kv.effective_to, kv.failure_reason
                FROM knowledge_versions kv
                JOIN knowledge_documents kd ON kd.tenant_id = kv.tenant_id AND kd.id = kv.document_id
                WHERE kv.tenant_id = ? AND kv.document_id = ? AND kv.content_hash = ?
                ORDER BY kv.version_no
                LIMIT 1
                """, (rs, rowNum) -> new KnowledgeVersionView(rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class), rs.getString("title"), rs.getString("category_code"),
                rs.getInt("version_no"), rs.getString("status"), rs.getTimestamp("effective_from").toInstant(),
                instant(rs.getTimestamp("effective_to")), rs.getString("failure_reason"), true),
                tenantId, documentId, contentHash).stream().findFirst();
    }

    public Optional<DocumentMetadata> findActiveDocument(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                SELECT title, category_code FROM knowledge_documents
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> new DocumentMetadata(rs.getString("title"), rs.getString("category_code")),
                tenantId, documentId).stream().findFirst();
    }

    public int nextVersionNo(UUID tenantId, UUID documentId) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) + 1 FROM knowledge_versions
                WHERE tenant_id = ? AND document_id = ?
                """, Integer.class, tenantId, documentId);
        return next == null ? 1 : next;
    }

    public void insertVersion(UUID tenantId, UUID documentId, UUID versionId, int versionNo,
            String contentHash, String filePath, String contentType, Instant effectiveFrom, Instant effectiveTo) {
        jdbc.update("""
                INSERT INTO knowledge_versions(id, tenant_id, document_id, version_no, content_hash,
                    file_path, content_type, status, effective_from, effective_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?)
                """, versionId, tenantId, documentId, versionNo, contentHash, filePath, contentType,
                Timestamp.from(effectiveFrom), timestamp(effectiveTo));
        jdbc.update("""
                INSERT INTO outbox_jobs(id, tenant_id, aggregate_id, job_type, status)
                VALUES (?, ?, ?, 'KNOWLEDGE_INGEST', 'READY')
                ON CONFLICT (tenant_id, aggregate_id, job_type) DO NOTHING
                """, UUID.randomUUID(), tenantId, versionId);
    }

    public Optional<VersionRecord> findVersion(UUID tenantId, UUID versionId) {
        return jdbc.query("""
                SELECT kv.id, kv.tenant_id, kv.document_id, kv.version_no, kv.content_hash,
                    kv.file_path, kv.content_type, kv.status, kv.effective_from, kv.effective_to,
                    kv.failure_reason, kd.title, kd.category_code, kd.status AS document_status
                FROM knowledge_versions kv
                JOIN knowledge_documents kd ON kd.tenant_id = kv.tenant_id AND kd.id = kv.document_id
                WHERE kv.tenant_id = ? AND kv.id = ?
                """, (rs, rowNum) -> new VersionRecord(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getInt("version_no"), rs.getString("content_hash"), rs.getString("file_path"),
                rs.getString("content_type"), rs.getString("status"),
                rs.getTimestamp("effective_from").toInstant(), instant(rs.getTimestamp("effective_to")),
                rs.getString("failure_reason"), rs.getString("title"), rs.getString("category_code"),
                rs.getString("document_status")),
                tenantId, versionId).stream().findFirst();
    }

    @Transactional
    public void requeueFailedVersion(UUID tenantId, UUID versionId) {
        deleteChunks(tenantId, versionId);
        jdbc.update("""
                UPDATE knowledge_versions SET status = 'UPLOADED', failure_reason = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'FAILED'
                """, tenantId, versionId);
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'READY', attempts = 0, available_at = now(),
                    lease_until = NULL, lease_owner = NULL, last_error = NULL, updated_at = now()
                WHERE tenant_id = ? AND aggregate_id = ? AND job_type = 'KNOWLEDGE_INGEST' AND status = 'FAILED'
                """, tenantId, versionId);
    }

    public void markProcessing(UUID tenantId, UUID versionId) {
        jdbc.update("""
                UPDATE knowledge_versions SET status = 'PROCESSING', failure_reason = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('UPLOADED', 'PROCESSING')
                """, tenantId, versionId);
    }

    public void deleteChunks(UUID tenantId, UUID versionId) {
        jdbc.update("DELETE FROM knowledge_chunks WHERE tenant_id = ? AND version_id = ?", tenantId, versionId);
    }

    public void markReady(UUID tenantId, UUID versionId) {
        jdbc.update("""
                UPDATE knowledge_versions SET status = 'READY', failure_reason = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PROCESSING'
                """, tenantId, versionId);
    }

    public void markFailed(UUID tenantId, UUID versionId, String code) {
        jdbc.update("""
                UPDATE knowledge_versions SET status = 'FAILED', failure_reason = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status <> 'PUBLISHED'
                """, code, tenantId, versionId);
    }

    public void insertChunk(UUID tenantId, UUID versionId, UUID chunkId, int chunkIndex,
            String content, Integer pageNo, String sectionTitle, float[] embedding, String modelName) {
        jdbc.update("""
                INSERT INTO knowledge_chunks(id, tenant_id, version_id, chunk_index, content,
                    page_no, section_title, embedding, embedding_model)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)
                ON CONFLICT (tenant_id, version_id, chunk_index) DO NOTHING
                """, chunkId, tenantId, versionId, chunkIndex, content, pageNo, sectionTitle,
                vectorLiteral(embedding), modelName);
    }

    @Transactional
    public Optional<IngestionJob> claimIngestionJob(String workerId) {
        var jobs = jdbc.query("""
                SELECT id, tenant_id, aggregate_id FROM outbox_jobs
                WHERE job_type = 'KNOWLEDGE_INGEST'
                  AND (status = 'READY' OR (status = 'LEASED' AND lease_until < now()))
                  AND available_at <= now()
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """, (rs, rowNum) -> new IngestionJob(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("aggregate_id", UUID.class)));
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        var job = jobs.getFirst();
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'LEASED', attempts = attempts + 1,
                    lease_owner = ?, lease_until = now() + interval '60 seconds',
                    lease_version = lease_version + 1, updated_at = now()
                WHERE id = ?
                """, workerId, job.id());
        return Optional.of(job);
    }

    public void completeJob(UUID jobId) {
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'DONE', lease_owner = NULL, lease_until = NULL,
                    last_error = NULL, updated_at = now() WHERE id = ? AND status = 'LEASED'
                """, jobId);
    }

    public void failJob(UUID jobId, String errorCode) {
        jdbc.update("""
                UPDATE outbox_jobs SET status = 'FAILED', lease_owner = NULL, lease_until = NULL,
                    last_error = ?, updated_at = now() WHERE id = ? AND status = 'LEASED'
                """, errorCode, jobId);
    }

    @Transactional
    public void publish(UUID tenantId, UUID versionId, Instant publishedAt) {
        var versions = jdbc.query("""
                SELECT id, document_id, status, effective_from, effective_to
                FROM knowledge_versions WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, (rs, rowNum) -> new PublishVersion(rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("status"),
                rs.getTimestamp("effective_from").toInstant(), instant(rs.getTimestamp("effective_to"))),
                tenantId, versionId);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("RESOURCE_NOT_FOUND");
        }
        var version = versions.getFirst();
        if (!"READY".equals(version.status())) {
            throw new IllegalStateException("VERSION_NOT_READY");
        }

        var documents = jdbc.query("""
                SELECT id, current_published_version_id FROM knowledge_documents
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE' FOR UPDATE
                """, (rs, rowNum) -> new DocumentRecord(rs.getObject("id", UUID.class),
                rs.getObject("current_published_version_id", UUID.class)), tenantId, version.documentId());
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("RESOURCE_NOT_FOUND");
        }
        UUID currentVersionId = documents.getFirst().currentVersionId();
        if (currentVersionId != null) {
            var currentRows = jdbc.query("""
                    SELECT effective_from, effective_to FROM knowledge_versions
                    WHERE tenant_id = ? AND id = ? FOR UPDATE
                    """, (rs, rowNum) -> new PublishVersion(currentVersionId, version.documentId(), "PUBLISHED",
                    rs.getTimestamp("effective_from").toInstant(), instant(rs.getTimestamp("effective_to"))),
                    tenantId, currentVersionId);
            if (!currentRows.isEmpty()) {
                var current = currentRows.getFirst();
                if (!version.effectiveFrom().isAfter(current.effectiveFrom())) {
                    throw new IllegalStateException("EFFECTIVE_FROM_MUST_ADVANCE");
                }
                if (current.effectiveTo() == null || current.effectiveTo().isAfter(version.effectiveFrom())) {
                    jdbc.update("""
                            UPDATE knowledge_versions SET effective_to = ?, updated_at = now()
                            WHERE tenant_id = ? AND id = ?
                            """, Timestamp.from(version.effectiveFrom()), tenantId, currentVersionId);
                }
            }
        }

        Boolean overlap = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM knowledge_versions
                    WHERE tenant_id = ? AND document_id = ? AND status = 'PUBLISHED' AND id <> ?
                      AND effective_from < COALESCE(?, 'infinity'::timestamptz)
                      AND COALESCE(effective_to, 'infinity'::timestamptz) > ?
                )
                """, Boolean.class, tenantId, version.documentId(), versionId,
                timestamp(version.effectiveTo()), Timestamp.from(version.effectiveFrom()));
        if (Boolean.TRUE.equals(overlap)) {
            throw new IllegalStateException("POLICY_EFFECTIVE_RANGE_OVERLAP");
        }
        jdbc.update("""
                UPDATE knowledge_versions SET status = 'PUBLISHED', published_at = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'READY'
                """, Timestamp.from(publishedAt), tenantId, versionId);
        jdbc.update("""
                UPDATE knowledge_documents SET current_published_version_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, versionId, tenantId, version.documentId());
    }

    private static String vectorLiteral(float[] vector) {
        var values = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                values.append(',');
            }
            values.append(Float.toString(vector[index]));
        }
        return values.append(']').toString();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record VersionRecord(UUID id, UUID tenantId, UUID documentId, int versionNo, String contentHash,
            String filePath, String contentType, String status, Instant effectiveFrom, Instant effectiveTo,
            String failureReason, String title, String categoryCode, String documentStatus) {
    }

    public record DocumentMetadata(String title, String categoryCode) {
    }

    public record IngestionJob(UUID id, UUID tenantId, UUID versionId) {
    }

    private record PublishVersion(UUID id, UUID documentId, String status, Instant effectiveFrom,
            Instant effectiveTo) {
    }

    private record DocumentRecord(UUID id, UUID currentVersionId) {
    }
}
