package com.aftercare.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class MockTransferDraftPlanTest {
    private final MockChatProvider provider = new MockChatProvider();
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Test
    void explicitInventoryApplicationPromptSelectsTheUserChosenSourceAndQuantity() {
        AgentPlan plan = provider.plan("请为 SKU-001 生成调拨建议并准备提交调拨审批草稿。商品ID " + SKU_ID
                + "，目标仓库ID " + TARGET_ID + "，建议来源仓库ID " + SOURCE_ID + "，数量 2 件。");

        assertThat(plan.intents()).contains(AgentPlan.Intent.TRANSFER_RECOMMENDATION,
                AgentPlan.Intent.TRANSFER_DRAFT);
        assertThat(plan.skuId()).isEqualTo(SKU_ID);
        assertThat(plan.targetWarehouseId()).isEqualTo(TARGET_ID);
        assertThat(plan.sourceWarehouseId()).isEqualTo(SOURCE_ID);
        assertThat(plan.quantity()).isEqualTo(2);
    }

    @Test
    void recommendationAloneDoesNotPrepareAnApprovalDraft() {
        AgentPlan plan = provider.plan("请为 SKU-001 生成调拨建议。商品ID " + SKU_ID
                + "，目标仓库ID " + TARGET_ID + "，数量 2 件。");

        assertThat(plan.intents()).contains(AgentPlan.Intent.TRANSFER_RECOMMENDATION)
                .doesNotContain(AgentPlan.Intent.TRANSFER_DRAFT);
    }

    @Test
    void incompleteTransferRequestStillSelectsInventoryAndClarificationIntents() {
        AgentPlan plan = provider.plan("目标仓缺少 SKU-001，请给出确定性调拨建议并说明排序依据。");

        assertThat(plan.intents()).contains(AgentPlan.Intent.INVENTORY_QUERY,
                AgentPlan.Intent.TRANSFER_RECOMMENDATION);
        assertThat(plan.skuId()).isNull();
        assertThat(plan.targetWarehouseId()).isNull();
        assertThat(plan.quantity()).isNull();
    }

    @Test
    void transferQuantityCanBeParsedFromReplenishmentWording() {
        AgentPlan plan = provider.plan("为目标仓补充 1 件 SKU-001，先计算安全库存约束下的候选来源。");

        assertThat(plan.intents()).contains(AgentPlan.Intent.TRANSFER_RECOMMENDATION);
        assertThat(plan.quantity()).isEqualTo(1);
    }
}
