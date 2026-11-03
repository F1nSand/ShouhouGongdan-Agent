package com.aftercare.knowledge;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final DocumentService documentService;
    private final PolicyRetriever policyRetriever;
    private final CurrentActorService currentActorService;

    public KnowledgeController(DocumentService documentService, PolicyRetriever policyRetriever,
            CurrentActorService currentActorService) {
        this.documentService = documentService;
        this.policyRetriever = policyRetriever;
        this.currentActorService = currentActorService;
    }

    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<KnowledgeVersionView>> upload(
            @RequestParam String title,
            @RequestParam String categoryCode,
            @RequestParam Instant effectiveFrom,
            @RequestParam(required = false) Instant effectiveTo,
            @RequestParam("file") MultipartFile file) throws IOException {
        var view = documentService.upload(currentActorService.currentActor(), command(title, categoryCode,
                effectiveFrom, effectiveTo, file));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(view));
    }

    @PostMapping(path = "/documents/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<KnowledgeVersionView>> uploadVersion(
            @PathVariable UUID documentId,
            @RequestParam String title,
            @RequestParam String categoryCode,
            @RequestParam Instant effectiveFrom,
            @RequestParam(required = false) Instant effectiveTo,
            @RequestParam("file") MultipartFile file) throws IOException {
        var view = documentService.uploadVersion(currentActorService.currentActor(), documentId,
                command(title, categoryCode, effectiveFrom, effectiveTo, file));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(view));
    }

    @GetMapping("/versions/{versionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeVersionView> version(@PathVariable UUID versionId) {
        return ApiResponse.ok(documentService.getVersion(currentActorService.currentActor(), versionId));
    }

    @PostMapping("/versions/{versionId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<KnowledgeVersionView>> retry(@PathVariable UUID versionId) {
        var view = documentService.retry(currentActorService.currentActor(), versionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(view));
    }

    @PostMapping("/versions/{versionId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> publish(@PathVariable UUID versionId) {
        documentService.publish(currentActorService.currentActor(), versionId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/search")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
    public ApiResponse<PolicyEvidence> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(policyRetriever.retrieve(currentActorService.currentActor(), request.query(),
                request.orderId(), request.categoryCode()));
    }

    private static DocumentService.UploadPolicyCommand command(String title, String categoryCode,
            Instant effectiveFrom, Instant effectiveTo, MultipartFile file) throws IOException {
        return new DocumentService.UploadPolicyCommand(title, categoryCode, effectiveFrom, effectiveTo,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    public record SearchRequest(@NotBlank @Size(max = 2000) String query, UUID orderId,
            @Size(max = 64) String categoryCode) {
    }
}
