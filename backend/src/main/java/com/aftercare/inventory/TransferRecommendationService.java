package com.aftercare.inventory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class TransferRecommendationService {
    private TransferRecommendationService() {
    }

    public static TransferRecommendation recommend(UUID skuId, UUID targetWarehouseId, int quantity,
            Instant calculatedAt, List<InventoryCandidate> inventory) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        var candidates = inventory.stream()
                .filter(row -> !row.sourceWarehouseId().equals(targetWarehouseId))
                .map(row -> new Candidate(row.sourceWarehouseId(), row.onHand() - row.reserved(),
                        Math.max(row.onHand() - row.reserved() - row.safetyStock(), 0), row.inventoryVersion(),
                        row.estimatedDays(), row.costCentsPerUnit()))
                .filter(candidate -> candidate.allocatable() >= quantity)
                .sorted(Comparator.comparingInt(Candidate::estimatedDays)
                        .thenComparingLong(Candidate::costCentsPerUnit)
                        .thenComparing(candidate -> candidate.sourceWarehouseId().toString()))
                .limit(3)
                .toList();
        String reason = candidates.isEmpty() ? "NO_SINGLE_SOURCE_WITH_SUFFICIENT_ALLOCATABLE_STOCK" : null;
        return new TransferRecommendation(skuId, targetWarehouseId, quantity, calculatedAt, candidates, reason);
    }

    public record InventoryCandidate(UUID sourceWarehouseId, int onHand, int reserved, int safetyStock,
            long inventoryVersion, int estimatedDays, long costCentsPerUnit) {
    }

    public record Candidate(UUID sourceWarehouseId, int available, int allocatable, long inventoryVersion,
            int estimatedDays, long costCentsPerUnit) {
    }

    public record TransferRecommendation(UUID skuId, UUID targetWarehouseId, int quantity, Instant calculatedAt,
            List<Candidate> candidates, String reason) {
        public TransferRecommendation {
            candidates = List.copyOf(candidates);
        }
    }
}
