package com.aftercare.approval;

import java.util.List;
import java.util.UUID;

import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sensitive-actions")
@PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
public class ApprovalController {
    private final SensitiveActionService actions;
    private final ApprovalService approvals;
    private final CurrentActorService currentActorService;

    public ApprovalController(SensitiveActionService actions, ApprovalService approvals,
            CurrentActorService currentActorService) {
        this.actions = actions;
        this.approvals = approvals;
        this.currentActorService = currentActorService;
    }

    @GetMapping
    public ApiResponse<List<SensitiveActionView>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(actions.list(currentActorService.currentActor(), page, size));
    }

    @GetMapping("/{actionId}")
    public ApiResponse<SensitiveActionView> get(@PathVariable UUID actionId) {
        return ApiResponse.ok(actions.get(currentActorService.currentActor(), actionId));
    }

    @PostMapping("/{actionId}/decision")
    public ApiResponse<DecisionResult> decide(@PathVariable UUID actionId,
            @Valid @RequestBody DecisionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.ok(approvals.decide(currentActorService.currentActor(), actionId,
                request.decision(), request.reason(), request.expectedVersion(), idempotencyKey));
    }

    public record DecisionRequest(@NotNull Decision decision,
            @NotBlank @Size(max = 500) String reason,
            long expectedVersion) {
    }
}
