package com.aftercare.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.aftercare.config.EmbeddingProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIngestionWorker {
    private final KnowledgeRepository repository;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingProperties embeddingProperties;
    private final boolean enabled;
    private final String workerId = UUID.randomUUID().toString();

    public KnowledgeIngestionWorker(KnowledgeRepository repository, EmbeddingProvider embeddingProvider,
            EmbeddingProperties embeddingProperties,
            @Value("${app.knowledge.worker-enabled:false}") boolean enabled) {
        this.repository = repository;
        this.embeddingProvider = embeddingProvider;
        this.embeddingProperties = embeddingProperties;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.knowledge.worker-delay:1000}")
    public void pollOutbox() {
        if (enabled) {
            processOne();
        }
    }

    public boolean processOne() {
        var maybeJob = repository.claimIngestionJob(workerId);
        if (maybeJob.isEmpty()) {
            return false;
        }
        var job = maybeJob.get();
        try {
            ingest(job.tenantId(), job.versionId());
            repository.completeJob(job.id());
        }
        catch (RuntimeException failure) {
            repository.markFailed(job.tenantId(), job.versionId(), "INGESTION_FAILED");
            repository.failJob(job.id(), "INGESTION_FAILED");
        }
        return true;
    }

    public void ingest(UUID tenantId, UUID versionId) {
        var version = repository.findVersion(tenantId, versionId)
                .orElseThrow(() -> new IllegalArgumentException("RESOURCE_NOT_FOUND"));
        if ("READY".equals(version.status()) || "PUBLISHED".equals(version.status())
                || "ARCHIVED".equals(version.status())) {
            return;
        }
        repository.markProcessing(tenantId, versionId);
        try {
            List<PageText> pages = extract(version);
            List<ChunkDraft> drafts = chunk(pages);
            if (drafts.isEmpty()) {
                repository.markFailed(tenantId, versionId, "EMPTY_TEXT");
                return;
            }
            var vectors = embeddingProvider.embedDocuments(drafts.stream().map(ChunkDraft::content).toList());
            if (vectors.size() != drafts.size()) {
                throw new IllegalStateException("Embedding provider returned an invalid result count");
            }
            for (int index = 0; index < drafts.size(); index++) {
                float[] vector = vectors.get(index);
                validateVector(vector);
                var draft = drafts.get(index);
                repository.insertChunk(tenantId, versionId, UUID.randomUUID(), draft.index(), draft.content(),
                        draft.pageNo(), null, vector, embeddingProvider.modelName());
            }
            repository.markReady(tenantId, versionId);
        }
        catch (IOException invalidFile) {
            repository.markFailed(tenantId, versionId, "DOCUMENT_READ_FAILED");
        }
        catch (RuntimeException failure) {
            repository.markFailed(tenantId, versionId, "INGESTION_FAILED");
        }
    }

    private List<PageText> extract(KnowledgeRepository.VersionRecord version) throws IOException {
        byte[] content = Files.readAllBytes(Path.of(version.filePath()));
        if ("application/pdf".equals(version.contentType())) {
            return extractPdf(content);
        }
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF", "");
        return List.of(new PageText(null, text));
    }

    private List<PageText> extractPdf(byte[] content) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            if (pdf.isEncrypted()) {
                throw new IOException("Encrypted PDF is not supported");
            }
            if (pdf.getNumberOfPages() > 200) {
                throw new IOException("PDF page limit exceeded");
            }
            var pages = new ArrayList<PageText>(pdf.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new PageText(page, stripper.getText(pdf)));
            }
            return pages;
        }
    }

    private static List<ChunkDraft> chunk(List<PageText> pages) {
        var result = new ArrayList<ChunkDraft>();
        for (var page : pages) {
            for (var chunk : TextChunker.chunk(page.text())) {
                result.add(new ChunkDraft(result.size(), page.pageNo(), chunk.text()));
            }
        }
        return result;
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != embeddingProperties.dimensions()) {
            throw new IllegalStateException("Embedding provider returned a vector with the wrong dimension");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Embedding provider returned a non-finite vector value");
            }
        }
    }

    private record PageText(Integer pageNo, String text) {
    }

    private record ChunkDraft(int index, Integer pageNo, String content) {
    }
}
