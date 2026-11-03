package com.aftercare.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 200;

    private final KnowledgeRepository repository;
    private final Path storageRoot;
    private final Clock clock;

    public DocumentService(KnowledgeRepository repository,
            @Value("${app.knowledge.storage-dir:./data/policies}") String storageDir, Clock clock) {
        this.repository = repository;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.clock = clock;
    }

    @Transactional
    public KnowledgeVersionView upload(ActorContext actor, UploadPolicyCommand command) {
        requireAdmin(actor);
        validateMetadata(command.title(), command.categoryCode(), command.effectiveFrom(), command.effectiveTo());
        var file = validateFile(command.fileName(), command.contentType(), command.content());
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String hash = sha256(command.content());
        Path path = storagePath(actor.tenantId(), versionId, file.extension());
        writeFile(path, command.content());
        try {
            repository.insertDocument(actor.tenantId(), documentId, command.title().trim(),
                    command.categoryCode().trim());
            repository.insertVersion(actor.tenantId(), documentId, versionId, 1, hash, path.toString(),
                    file.contentType(), command.effectiveFrom(), command.effectiveTo());
            return new KnowledgeVersionView(documentId, versionId, command.title().trim(),
                    command.categoryCode().trim(), 1, "UPLOADED", command.effectiveFrom(),
                    command.effectiveTo(), null, false);
        }
        catch (RuntimeException error) {
            deleteQuietly(path);
            throw error;
        }
    }

    @Transactional
    public KnowledgeVersionView uploadVersion(ActorContext actor, UUID documentId, UploadPolicyCommand command) {
        requireAdmin(actor);
        validateMetadata(command.title(), command.categoryCode(), command.effectiveFrom(), command.effectiveTo());
        var document = repository.findActiveDocument(actor.tenantId(), documentId)
                .orElseThrow(DocumentService::notFound);
        if (!document.title().equals(command.title().trim())
                || !document.categoryCode().equals(command.categoryCode().trim())) {
            throw new ApiException("INVALID_ARGUMENT", "新版本必须沿用文档的标题和政策类别", HttpStatus.BAD_REQUEST);
        }
        var file = validateFile(command.fileName(), command.contentType(), command.content());
        String hash = sha256(command.content());
        var existing = repository.findExistingVersion(actor.tenantId(), documentId, hash);
        if (existing.isPresent()) {
            var view = existing.get();
            if ("FAILED".equals(view.status())) {
                repository.requeueFailedVersion(actor.tenantId(), view.versionId());
                return new KnowledgeVersionView(view.documentId(), view.versionId(), document.title(),
                        document.categoryCode(), view.versionNo(), "UPLOADED", view.effectiveFrom(), view.effectiveTo(),
                        null, true);
            }
            return new KnowledgeVersionView(view.documentId(), view.versionId(), document.title(),
                    document.categoryCode(), view.versionNo(), view.status(), view.effectiveFrom(), view.effectiveTo(),
                    view.failureReason(), true);
        }

        int versionNo = repository.nextVersionNo(actor.tenantId(), documentId);
        UUID versionId = UUID.randomUUID();
        Path path = storagePath(actor.tenantId(), versionId, file.extension());
        writeFile(path, command.content());
        try {
            repository.insertVersion(actor.tenantId(), documentId, versionId, versionNo, hash, path.toString(),
                    file.contentType(), command.effectiveFrom(), command.effectiveTo());
            return new KnowledgeVersionView(documentId, versionId, document.title(),
                    document.categoryCode(), versionNo, "UPLOADED", command.effectiveFrom(),
                    command.effectiveTo(), null, false);
        }
        catch (RuntimeException error) {
            deleteQuietly(path);
            throw error;
        }
    }

    public KnowledgeVersionView getVersion(ActorContext actor, UUID versionId) {
        var version = repository.findVersion(actor.tenantId(), versionId).orElseThrow(DocumentService::notFound);
        return new KnowledgeVersionView(version.documentId(), version.id(), version.title(), version.categoryCode(),
                version.versionNo(), version.status(), version.effectiveFrom(), version.effectiveTo(),
                version.failureReason(), false);
    }

    @Transactional
    public KnowledgeVersionView retry(ActorContext actor, UUID versionId) {
        requireAdmin(actor);
        try {
            repository.requeueFailedVersion(actor.tenantId(), versionId);
        }
        catch (IllegalArgumentException missing) {
            if ("RESOURCE_NOT_FOUND".equals(missing.getMessage())) {
                throw notFound();
            }
            throw missing;
        }
        catch (IllegalStateException invalid) {
            String code = invalid.getMessage();
            String message = "VERSION_NOT_FAILED".equals(code)
                    ? "仅失败的知识版本可以重试" : "知识入库作业当前状态不允许重试";
            throw new ApiException(code, message, HttpStatus.CONFLICT);
        }

        var updated = getVersion(actor, versionId);
        return new KnowledgeVersionView(updated.documentId(), updated.versionId(), updated.title(),
                updated.categoryCode(), updated.versionNo(), updated.status(), updated.effectiveFrom(),
                updated.effectiveTo(), updated.failureReason(), true);
    }

    @Transactional
    public void publish(ActorContext actor, UUID versionId) {
        requireAdmin(actor);
        try {
            repository.publish(actor.tenantId(), versionId, clock.instant());
        }
        catch (IllegalArgumentException missing) {
            if ("RESOURCE_NOT_FOUND".equals(missing.getMessage())) {
                throw notFound();
            }
            throw missing;
        }
        catch (IllegalStateException invalid) {
            String code = invalid.getMessage();
            throw new ApiException(code, "知识版本当前状态不允许发布", HttpStatus.CONFLICT);
        }
    }

    private void validateMetadata(String title, String categoryCode, Instant effectiveFrom, Instant effectiveTo) {
        if (title == null || title.isBlank() || title.length() > 250
                || categoryCode == null || categoryCode.isBlank() || categoryCode.length() > 64
                || effectiveFrom == null || (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom))) {
            throw new ApiException("INVALID_ARGUMENT", "请提供有效的标题、类别和生效区间", HttpStatus.BAD_REQUEST);
        }
    }

    private FileInfo validateFile(String fileName, String declaredType, byte[] content) {
        if (fileName == null || content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw new ApiException("INVALID_ARGUMENT", "文件为空或超过 10 MB", HttpStatus.BAD_REQUEST);
        }
        String lowerName = Path.of(fileName).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String type = declaredType == null ? "" : declaredType.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".pdf")) {
            validatePdf(content);
            return new FileInfo(".pdf", "application/pdf");
        }
        if (lowerName.endsWith(".txt") && (type.isEmpty() || type.equals("text/plain")
                || type.equals("application/octet-stream"))) {
            return new FileInfo(".txt", "text/plain");
        }
        if (lowerName.endsWith(".md") && (type.isEmpty() || type.equals("text/markdown")
                || type.equals("text/plain") || type.equals("application/octet-stream"))) {
            return new FileInfo(".md", "text/markdown");
        }
        throw new ApiException("UNSUPPORTED_FILE_TYPE", "仅支持 PDF、Markdown 和 TXT 文件", HttpStatus.BAD_REQUEST);
    }

    private void validatePdf(byte[] content) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            if (pdf.isEncrypted()) {
                throw new ApiException("UNSUPPORTED_PDF", "暂不支持加密 PDF", HttpStatus.BAD_REQUEST);
            }
            if (pdf.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new ApiException("PDF_TOO_MANY_PAGES", "PDF 不能超过 200 页", HttpStatus.BAD_REQUEST);
            }
        }
        catch (IOException invalidPdf) {
            throw new ApiException("INVALID_PDF", "无法读取 PDF 文件", HttpStatus.BAD_REQUEST);
        }
    }

    private Path storagePath(UUID tenantId, UUID versionId, String extension) {
        Path file = storageRoot.resolve(tenantId.toString()).resolve(versionId + extension).normalize();
        if (!file.startsWith(storageRoot)) {
            throw new ApiException("INVALID_ARGUMENT", "文件存储路径无效", HttpStatus.BAD_REQUEST);
        }
        return file;
    }

    private void writeFile(Path path, byte[] content) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        catch (IOException error) {
            throw new ApiException("FILE_STORAGE_FAILED", "无法保存知识文件", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // A failed database write must not conceal the original error.
        }
    }

    private static void requireAdmin(ActorContext actor) {
        if (actor.role() != Role.ADMIN) {
            throw new ApiException("FORBIDDEN", "仅管理员可管理售后政策", HttpStatus.FORBIDDEN);
        }
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该知识文档或版本", HttpStatus.NOT_FOUND);
    }

    private record FileInfo(String extension, String contentType) {
    }

    public record UploadPolicyCommand(String title, String categoryCode, Instant effectiveFrom, Instant effectiveTo,
            String fileName, String contentType, byte[] content) {
        public UploadPolicyCommand {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }
}
