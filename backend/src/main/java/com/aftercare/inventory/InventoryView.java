package com.aftercare.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryView(UUID skuId, String skuCode, String skuName, Instant calculatedAt,
        List<WarehouseStock> warehouses) {
    public InventoryView {
        warehouses = List.copyOf(warehouses);
    }

    public record WarehouseStock(UUID warehouseId, String warehouseCode, String warehouseName, int onHand,
            int reserved, int available, int safetyStock, int allocatable, long inventoryVersion) {
    }
}
