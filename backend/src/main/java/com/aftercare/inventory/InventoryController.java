package com.aftercare.inventory;

import java.util.UUID;

import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
public class InventoryController {
    private final InventoryService inventoryService;
    private final CurrentActorService currentActorService;

    public InventoryController(InventoryService inventoryService, CurrentActorService currentActorService) {
        this.inventoryService = inventoryService;
        this.currentActorService = currentActorService;
    }

    @GetMapping
    public ApiResponse<InventoryView> query(@RequestParam String skuCode) {
        return ApiResponse.ok(inventoryService.queryInventory(currentActorService.currentActor(), skuCode));
    }

    @PostMapping("/recommendations")
    public ApiResponse<TransferRecommendationService.TransferRecommendation> recommend(
            @Valid @RequestBody RecommendationRequest request) {
        return ApiResponse.ok(inventoryService.recommend(currentActorService.currentActor(), request.skuId(),
                request.targetWarehouseId(), request.quantity()));
    }

    public record RecommendationRequest(@NotNull UUID skuId, @NotNull UUID targetWarehouseId,
            @Min(1) int quantity) {
    }
}
