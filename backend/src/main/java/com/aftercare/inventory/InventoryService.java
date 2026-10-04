package com.aftercare.inventory;

import java.time.Clock;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {
    private final InventoryRepository repository;
    private final Clock clock;

    public InventoryService(InventoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public InventoryView queryInventory(ActorContext actor, String skuCode) {
        var sku = repository.findActiveSkuByCode(actor.tenantId(), skuCode)
                .orElseThrow(InventoryService::notFound);
        var rows = repository.findBySkuCode(actor.tenantId(), skuCode);
        var warehouses = rows.stream().map(row -> new InventoryView.WarehouseStock(row.warehouseId(),
                row.warehouseCode(), row.warehouseName(), row.onHand(), row.reserved(),
                row.onHand() - row.reserved(), row.safetyStock(),
                Math.max(row.onHand() - row.reserved() - row.safetyStock(), 0), row.inventoryVersion())).toList();
        return new InventoryView(sku.id(), sku.skuCode(), sku.name(), clock.instant(), warehouses);
    }

    public TransferRecommendationService.TransferRecommendation recommend(ActorContext actor, UUID skuId,
            UUID targetWarehouseId, int quantity) {
        if (quantity <= 0) {
            throw new ApiException("INVALID_ARGUMENT", "调拨数量必须大于 0", HttpStatus.BAD_REQUEST);
        }
        if (repository.findActiveSkuId(actor.tenantId(), skuId).isEmpty()
                || !repository.activeWarehouseExists(actor.tenantId(), targetWarehouseId)) {
            throw notFound();
        }
        var candidates = repository.findTransferCandidates(actor.tenantId(), skuId, targetWarehouseId);
        return TransferRecommendationService.recommend(skuId, targetWarehouseId, quantity,
                clock.instant(), candidates);
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该商品或库存", HttpStatus.NOT_FOUND);
    }
}
