package com.aftercare.agent;

import java.util.List;
import java.util.UUID;

public record AgentPlan(List<Intent> intents, String orderNo, String skuCode,
        UUID skuId, UUID targetWarehouseId, Integer quantity, String categoryCode,
        Long refundAmountCents, String title, String description, String priority, String refundReason,
        UUID sourceWarehouseId) {
    public AgentPlan {
        intents = intents == null || intents.isEmpty() ? List.of(Intent.UNKNOWN) : List.copyOf(intents);
    }

    public enum Intent {
        ORDER_QUERY,
        LOGISTICS,
        POLICY_QA,
        TICKET_DRAFT,
        REFUND_DRAFT,
        INVENTORY_QUERY,
        TRANSFER_RECOMMENDATION,
        TRANSFER_DRAFT,
        UNKNOWN
    }
}
