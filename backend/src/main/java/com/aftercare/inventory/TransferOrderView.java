package com.aftercare.inventory;

import java.time.Instant;
import java.util.UUID;

public record TransferOrderView(UUID id, UUID actionId, UUID sourceWarehouseId,
        UUID targetWarehouseId, UUID skuId, int quantity, String status, Instant expiresAt) {
}
