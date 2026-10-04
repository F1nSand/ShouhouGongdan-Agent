package com.aftercare.audit;

import java.time.Instant;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private final AuditRepository repository;
    private final CurrentActorService currentActor;

    public AuditController(AuditRepository repository, CurrentActorService currentActor) {
        this.repository = repository;
        this.currentActor = currentActor;
    }

    @GetMapping
    public ApiResponse<AuditRepository.AuditPage> list(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 1 || size < 1 || size > 100 || (from != null && to != null && !to.isAfter(from))) {
            throw new ApiException("INVALID_ARGUMENT", "审计查询时间区间或分页参数无效", HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.ok(repository.list(currentActor.currentActor().tenantId(),
                traceId, resourceId, action, from, to, page, size));
    }
}
