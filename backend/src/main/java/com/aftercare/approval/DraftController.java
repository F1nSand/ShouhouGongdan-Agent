package com.aftercare.approval;

import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/action-drafts")
public class DraftController {
    private final ActionDraftService service;
    private final CurrentActorService currentActorService;

    public DraftController(ActionDraftService service, CurrentActorService currentActorService) {
        this.service = service;
        this.currentActorService = currentActorService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<DraftView>> createManual(
            @Valid @RequestBody CreateManualDraftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        if (!"CREATE_TICKET".equals(request.type())) {
            throw new ApiException("UNSUPPORTED_DRAFT_TYPE", "人工草稿当前只支持创建工单", HttpStatus.BAD_REQUEST);
        }
        var payload = request.typedPayload();
        var view = service.createManualDraft(currentActorService.currentActor(),
                new ManualTicketDraftCommand(payload.orderId(), payload.category(), payload.title(),
                        payload.description(), payload.priority()), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view));
    }

    @GetMapping("/{draftId}")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
    public ApiResponse<DraftView> get(@PathVariable UUID draftId) {
        return ApiResponse.ok(service.get(currentActorService.currentActor(), draftId));
    }

    @PostMapping("/{draftId}/confirm")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
    public ApiResponse<ConfirmedActionResult> confirm(@PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = currentActorService.currentActor();
        var result = service.confirm(actor, draftId, idempotencyKey);
        return ApiResponse.ok(result);
    }

    @PostMapping("/{draftId}/invalidate")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'ADMIN')")
    public ApiResponse<DraftView> invalidate(@PathVariable UUID draftId,
            @Valid @RequestBody InvalidateDraftRequest request) {
        return ApiResponse.ok(service.invalidate(currentActorService.currentActor(), draftId, request.reason()));
    }

    public record CreateManualDraftRequest(@NotBlank String type, @NotNull @Valid TypedPayload typedPayload) {
    }

    public record TypedPayload(UUID orderId,
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 5000) String description,
            @NotBlank String priority) {
    }

    public record InvalidateDraftRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
