package com.aftercare.logistics;

import java.util.UUID;

import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/logistics")
@PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
public class LogisticsController {
    private final LogisticsService logisticsService;
    private final CurrentActorService currentActorService;

    public LogisticsController(LogisticsService logisticsService, CurrentActorService currentActorService) {
        this.logisticsService = logisticsService;
        this.currentActorService = currentActorService;
    }

    @GetMapping
    public ApiResponse<LogisticsView> query(@PathVariable UUID orderId) {
        return ApiResponse.ok(logisticsService.queryLogistics(currentActorService.currentActor(), orderId));
    }
}
