package com.aftercare.agent;

import java.util.List;
import java.util.UUID;

import com.aftercare.approval.DraftView;
import com.aftercare.inventory.InventoryView;
import com.aftercare.inventory.TransferRecommendationService;
import com.aftercare.knowledge.AssistantAnswer;
import com.aftercare.knowledge.CitationValidator;
import com.aftercare.knowledge.PolicyEvidence;
import com.aftercare.logistics.LogisticsView;
import com.aftercare.order.OrderView;
import org.springframework.stereotype.Component;

@Component
public class AnswerAssembler {
    public AssembledAnswer assemble(AgentPlan plan, OrderView order, LogisticsView logistics,
            InventoryView inventory, TransferRecommendationService.TransferRecommendation transfer,
            PolicyEvidence policy, List<DraftView> drafts, List<String> toolErrors) {
        var lines = new java.util.ArrayList<String>();
        if (order != null) {
            lines.add("已查询订单 " + order.orderNo() + "，当前状态为「" + order.status() + "」。");
        }
        if (logistics != null) {
            lines.add(logistics.exceptionCodes().isEmpty()
                    ? "物流信息已查询，当前没有识别到异常。"
                    : "物流信息显示异常：" + String.join("、", logistics.exceptionCodes()) + "。");
        }
        AssistantAnswer.Citation citation = null;
        if (policy != null && !policy.chunks().isEmpty()) {
            var chunk = policy.chunks().getFirst();
            String quote = quote(chunk.content());
            citation = new AssistantAnswer.Citation(chunk.chunkId(), quote);
            lines.add("根据《" + chunk.documentTitle() + "》第 " + chunk.versionNo() + " 版政策：" + quote);
        }
        else if (plan.intents().contains(AgentPlan.Intent.POLICY_QA)) {
            lines.add("当前没有检索到可引用的已发布政策内容，我不会仅凭模型记忆判断政策资格。");
        }
        if (inventory != null) {
            String stock = inventory.warehouses().stream()
                    .map(row -> row.warehouseName() + "可调配 " + row.allocatable())
                    .collect(java.util.stream.Collectors.joining("；"));
            lines.add("商品 " + inventory.skuCode() + " 的库存查询结果：" + stock + (stock.isBlank() ? "暂无库存记录。" : "。"));
        }
        if (transfer != null) {
            lines.add(transfer.candidates().isEmpty()
                    ? "没有满足安全库存约束的调拨方案。"
                    : "已生成 " + transfer.candidates().size() + " 个调拨建议；这只是建议，提交后仍需人工审批。");
        }
        if (!drafts.isEmpty()) {
            lines.add("已准备 " + drafts.size() + " 个待确认草稿。确认前不会创建工单或提交敏感操作。");
        }
        if (!toolErrors.isEmpty()) {
            lines.add("部分查询未完成：" + String.join("、", toolErrors) + "。可重试查询或转人工处理。");
        }
        if (plan.intents().contains(AgentPlan.Intent.UNKNOWN) || lines.isEmpty()) {
            lines.add("请补充订单号、商品编码或希望处理的售后事项，我再继续查询。");
        }
        String answerText = String.join("\n", lines);
        var answer = new AssistantAnswer(answerText, citation == null ? List.of() : List.of(citation));
        var evidence = policy == null ? new PolicyEvidence(List.of()) : policy;
        var validated = CitationValidator.validate(answer, evidence);
        return new AssembledAnswer(answer, validated, drafts.stream().map(draft -> new SuggestedAction(
                draft.id(), draft.type(), summary(draft))).toList(), !drafts.isEmpty());
    }

    private static String quote(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int end = Math.min(content.length(), 240);
        return content.substring(0, end).trim();
    }

    private static String summary(DraftView draft) {
        var payload = draft.payload();
        if (payload != null && payload.hasNonNull("title")) {
            return payload.get("title").asText();
        }
        return switch (draft.type()) {
            case "REQUEST_REFUND" -> "退款申请待确认并进入人工审批";
            case "REQUEST_STOCK_TRANSFER" -> "库存调拨申请待确认并进入人工审批";
            default -> "售后工单草稿待确认";
        };
    }

    public record SuggestedAction(UUID draftId, String type, String summary) {
    }

    public record AssembledAnswer(AssistantAnswer answer, CitationValidator.ValidatedAnswer validated,
            List<SuggestedAction> suggestedActions, boolean needsHuman) {
        public AssembledAnswer {
            suggestedActions = List.copyOf(suggestedActions);
        }
    }
}
