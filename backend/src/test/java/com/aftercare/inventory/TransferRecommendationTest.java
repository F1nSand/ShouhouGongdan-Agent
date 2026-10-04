package com.aftercare.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRecommendationTest {
    private static final UUID SKU_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    @Test
    void recommendationRespectsSafetyStock() {
        var source = candidate("30000000-0000-0000-0000-000000000001", 20, 5, 3, 0, 100);

        var fits = TransferRecommendationService.recommend(SKU_ID, TARGET_ID, 12, NOW, List.of(source));
        var tooLarge = TransferRecommendationService.recommend(SKU_ID, TARGET_ID, 13, NOW, List.of(source));

        assertThat(fits.candidates()).hasSize(1);
        assertThat(fits.candidates().getFirst().available()).isEqualTo(15);
        assertThat(fits.candidates().getFirst().allocatable()).isEqualTo(12);
        assertThat(tooLarge.candidates()).isEmpty();
    }

    @Test
    void recommendationSortsByDaysThenCostThenWarehouseId() {
        var slowerCheaper = candidate("30000000-0000-0000-0000-000000000001", 20, 0, 0, 3, 100);
        var fasterExpensive = candidate("30000000-0000-0000-0000-000000000002", 20, 0, 0, 2, 300);
        var fasterCheaperHighId = candidate("30000000-0000-0000-0000-000000000003", 20, 0, 0, 2, 200);
        var fasterCheaperLowId = candidate("30000000-0000-0000-0000-000000000004", 20, 0, 0, 2, 200);

        var result = TransferRecommendationService.recommend(SKU_ID, TARGET_ID, 1, NOW,
                List.of(slowerCheaper, fasterExpensive, fasterCheaperHighId, fasterCheaperLowId));

        assertThat(result.candidates()).extracting(TransferRecommendationService.Candidate::sourceWarehouseId)
                .containsExactly(fasterCheaperHighId.sourceWarehouseId(), fasterCheaperLowId.sourceWarehouseId(),
                        fasterExpensive.sourceWarehouseId());
    }

    @Test
    void excludesTargetWarehouseAndReturnsAtMostThreeOptions() {
        var sources = List.of(
                candidate(TARGET_ID.toString(), 100, 0, 0, 0, 1),
                candidate("30000000-0000-0000-0000-000000000001", 100, 0, 0, 1, 1),
                candidate("30000000-0000-0000-0000-000000000002", 100, 0, 0, 2, 1),
                candidate("30000000-0000-0000-0000-000000000003", 100, 0, 0, 3, 1),
                candidate("30000000-0000-0000-0000-000000000004", 100, 0, 0, 4, 1));

        var result = TransferRecommendationService.recommend(SKU_ID, TARGET_ID, 1, NOW, sources);

        assertThat(result.candidates()).hasSize(3)
                .allSatisfy(candidate -> assertThat(candidate.sourceWarehouseId()).isNotEqualTo(TARGET_ID));
    }

    private TransferRecommendationService.InventoryCandidate candidate(String warehouseId, int onHand,
            int reserved, int safetyStock, int estimatedDays, long costCentsPerUnit) {
        return new TransferRecommendationService.InventoryCandidate(UUID.fromString(warehouseId), onHand, reserved,
                safetyStock, 7L, estimatedDays, costCentsPerUnit);
    }
}
