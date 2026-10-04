package com.aftercare.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.aftercare.approval.ActionDraftService;
import com.aftercare.approval.DraftView;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.inventory.InventoryView;
import com.aftercare.inventory.TransferRecommendationService;
import com.aftercare.knowledge.AssistantAnswer;
import com.aftercare.knowledge.PolicyEvidence;
import com.aftercare.logistics.LogisticsView;
import com.aftercare.order.OrderView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentOrchestrator {
    private final ChatProvider chatProvider;
    private final ToolGateway tools;
    private final AgentRunRepository runs;
    private final RunEventService events;
    private final RunEvidenceRegistry evidenceRegistry;
    private final ActionDraftService drafts;
    private final AnswerAssembler answerAssembler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor modelCallExecutor;
    private final TaskExecutor toolCallExecutor;

    public AgentOrchestrator(ChatProvider chatProvider, ToolGateway tools, AgentRunRepository runs,
            RunEventService events, RunEvidenceRegistry evidenceRegistry, ActionDraftService drafts,
            AnswerAssembler answerAssembler, ObjectMapper objectMapper, Clock clock,
            PlatformTransactionManager transactionManager,
            @Qualifier("modelCallExecutor") TaskExecutor modelCallExecutor,
            @Qualifier("toolCallExecutor") TaskExecutor toolCallExecutor) {
        this.chatProvider = chatProvider;
        this.tools = tools;
        this.runs = runs;
        this.events = events;
        this.evidenceRegistry = evidenceRegistry;
        this.drafts = drafts;
        this.answerAssembler = answerAssembler;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.modelCallExecutor = modelCallExecutor;
        this.toolCallExecutor = toolCallExecutor;
    }

    public void run(ActorContext actor, UUID conversationId, UUID runId, String userMessage) {
        var budget = new AgentBudget(clock, Duration.ofSeconds(60));
        if (runs.startRun(actor.tenantId(), runId) != 1) {
            return;
        }
        var orderHolder = new Holder<OrderView>();
        var logisticsHolder = new Holder<LogisticsView>();
        var inventoryHolder = new Holder<InventoryView>();
        var transferHolder = new Holder<TransferRecommendationService.TransferRecommendation>();
        var policyHolder = new Holder<PolicyEvidence>();
        var draftViews = new ArrayList<DraftView>();
        var errors = new ArrayList<String>();
        try {
            budget.consumeModelCall();
            ensureNotCancelled(actor, runId);
            AgentPlan plan = sanitize(callModel(userMessage));
            budget.checkTime();

            boolean needsOrder = plan.orderNo() != null && plan.intents().stream().anyMatch(intent -> SetOfOrderDependent.contains(intent));
            if (needsOrder) {
                orderHolder.value = queryOrder(actor, runId, budget, plan.orderNo(), 1, errors);
                if (orderHolder.value == null && plan.intents().stream().anyMatch(intent -> SetOfOrderDependent.contains(intent))) {
                    errors.add("未找到该订单或订单查询失败");
                }
            }
            if (plan.intents().contains(AgentPlan.Intent.LOGISTICS)) {
                if (orderHolder.value == null) {
                    errors.add("查询物流需要有效订单号");
                }
                else {
                    logisticsHolder.value = call(actor, runId, budget, "query_logistics", parameters(Map.of(
                            "orderId", orderHolder.value.orderId())), 2, LogisticsView.class, errors);
                }
            }
            if (plan.intents().contains(AgentPlan.Intent.POLICY_QA)) {
                if (plan.orderNo() != null && orderHolder.value == null) {
                    errors.add("订单未查到，未检索订单日期对应的政策");
                }
                else {
                    var parameters = objectMapper.createObjectNode();
                    parameters.put("query", userMessage);
                    if (orderHolder.value != null) {
                        parameters.put("orderId", orderHolder.value.orderId().toString());
                    }
                    if (plan.categoryCode() != null && !plan.categoryCode().isBlank()) {
                        parameters.put("categoryCode", plan.categoryCode());
                    }
                    policyHolder.value = call(actor, runId, budget, "retrieve_policy", parameters, 3,
                            PolicyEvidence.class, errors);
                }
            }
            boolean needsInventoryLookup = plan.intents().contains(AgentPlan.Intent.INVENTORY_QUERY)
                    || plan.intents().contains(AgentPlan.Intent.TRANSFER_RECOMMENDATION)
                    || plan.intents().contains(AgentPlan.Intent.TRANSFER_DRAFT);
            if (needsInventoryLookup) {
                if (plan.skuCode() == null) {
                    errors.add("查询库存需要商品编码");
                }
                else {
                    inventoryHolder.value = call(actor, runId, budget, "query_inventory",
                            parameters(Map.of("skuCode", plan.skuCode())), 4, InventoryView.class, errors);
                }
            }
            UUID resolvedSkuId = inventoryHolder.value == null ? plan.skuId() : inventoryHolder.value.skuId();
            if (plan.intents().contains(AgentPlan.Intent.TRANSFER_RECOMMENDATION)) {
                var missingTransferFields = new ArrayList<String>();
                if (resolvedSkuId == null) missingTransferFields.add("有效商品");
                if (plan.targetWarehouseId() == null) missingTransferFields.add("目标仓库");
                if (plan.quantity() == null || plan.quantity() <= 0) missingTransferFields.add("正整数数量");
                if (!missingTransferFields.isEmpty()) {
                    errors.add("调拨建议还需要" + String.join("、", missingTransferFields) + "；目前没有生成建议");
                }
                else {
                    transferHolder.value = call(actor, runId, budget, "recommend_transfer", parameters(Map.of(
                            "skuId", resolvedSkuId, "targetWarehouseId", plan.targetWarehouseId(),
                            "quantity", plan.quantity())), 5,
                            TransferRecommendationService.TransferRecommendation.class, errors);
                }
            }
            if (plan.intents().contains(AgentPlan.Intent.TRANSFER_DRAFT)) {
                var selectedCandidate = transferHolder.value == null || plan.sourceWarehouseId() == null
                        ? null : transferHolder.value.candidates().stream()
                                .filter(candidate -> candidate.sourceWarehouseId().equals(plan.sourceWarehouseId()))
                                .findFirst().orElse(null);
                var missingDraftFields = new ArrayList<String>();
                if (resolvedSkuId == null) missingDraftFields.add("有效商品");
                if (plan.sourceWarehouseId() == null) missingDraftFields.add("已选来源仓");
                if (plan.targetWarehouseId() == null) missingDraftFields.add("目标仓");
                if (plan.quantity() == null || plan.quantity() <= 0) missingDraftFields.add("正整数数量");
                if (!missingDraftFields.isEmpty()) {
                    errors.add("调拨申请草稿还需要" + String.join("、", missingDraftFields)
                            + "；目前没有创建申请");
                }
                else if (selectedCandidate == null) {
                    errors.add("已选来源仓不在当前安全库存建议中；请刷新建议后重新申请");
                }
                else {
                    var parameters = objectMapper.createObjectNode();
                    parameters.put("type", "STOCK_TRANSFER");
                    var typed = parameters.putObject("typedPayload");
                    typed.put("skuId", resolvedSkuId.toString());
                    typed.put("sourceWarehouseId", selectedCandidate.sourceWarehouseId().toString());
                    typed.put("targetWarehouseId", plan.targetWarehouseId().toString());
                    typed.put("quantity", plan.quantity());
                    DraftView draft = call(actor, runId, budget, "draft_sensitive_action", parameters,
                            8, DraftView.class, errors);
                    if (draft != null) {
                        draftViews.add(draft);
                    }
                }
            }
            if (plan.intents().contains(AgentPlan.Intent.TICKET_DRAFT)) {
                var parameters = objectMapper.createObjectNode();
                if (orderHolder.value != null) {
                    parameters.put("orderId", orderHolder.value.orderId().toString());
                }
                parameters.put("category", plan.categoryCode() == null ? "CUSTOMER_SUPPORT" : plan.categoryCode());
                parameters.put("title", plan.title());
                parameters.put("description", plan.description());
                parameters.put("priority", plan.priority());
                DraftView draft = call(actor, runId, budget, "draft_ticket", parameters,
                        6, DraftView.class, errors);
                if (draft != null) {
                    draftViews.add(draft);
                }
            }
            if (plan.intents().contains(AgentPlan.Intent.REFUND_DRAFT)) {
                if (orderHolder.value == null || plan.refundAmountCents() == null || plan.refundAmountCents() <= 0) {
                    errors.add("退款申请需要有效订单及明确的退款金额；目前没有创建草稿");
                }
                else {
                    var parameters = objectMapper.createObjectNode();
                    parameters.put("type", "REFUND_REQUEST");
                    var typed = parameters.putObject("typedPayload");
                    typed.put("orderId", orderHolder.value.orderId().toString());
                    typed.put("amountCents", plan.refundAmountCents());
                    typed.put("reason", plan.refundReason() == null || plan.refundReason().isBlank()
                            ? "用户申请退款" : plan.refundReason());
                    DraftView draft = call(actor, runId, budget, "draft_sensitive_action", parameters,
                            7, DraftView.class, errors);
                    if (draft != null) {
                        draftViews.add(draft);
                    }
                }
            }
            budget.checkTime();
            var policy = policyHolder.value == null
                    ? evidenceRegistry.snapshot(runId) : policyHolder.value;
            var assembled = answerAssembler.assemble(plan, orderHolder.value, logisticsHolder.value,
                    inventoryHolder.value, transferHolder.value, policy, draftViews, errors);
            complete(actor, conversationId, runId, assembled, policy, draftViews);
        }
        catch (RuntimeException failure) {
            fail(actor, runId, errorCode(failure));
        }
        finally {
            evidenceRegistry.clear(runId);
        }
    }

    private OrderView queryOrder(ActorContext actor, UUID runId, AgentBudget budget,
            String orderNo, int callNo, List<String> errors) {
        return call(actor, runId, budget, "query_order", parameters(Map.of("orderNo", orderNo)), callNo,
                OrderView.class, errors);
    }

    private <T> T call(ActorContext actor, UUID runId, AgentBudget budget, String name,
            JsonNode parameters, int callNo, Class<T> resultType, List<String> errors) {
        ensureNotCancelled(actor, runId);
        budget.checkTime();
        budget.consumeToolCall();
        String invocationId = "call-" + callNo;
        events.appendSystem(actor.tenantId(), runId, "tool.started",
                Map.of("invocationId", invocationId, "toolName", name));
        long started = System.nanoTime();
        ToolResult result = name.startsWith("draft_")
                ? tools.execute(actor, runId, invocationId, name, parameters)
                : callTool(actor, runId, invocationId, name, parameters);
        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        String status = result.success() ? "SUCCEEDED" : "FAILED";
        String inputSummary = json(Map.of("fieldNames", fieldNames(parameters)));
        String outputSummary = json(Map.of("success", result.success(),
                "errorCode", result.errorCode() == null ? "" : result.errorCode()));
        runs.insertToolInvocation(actor.tenantId(), runId, invocationId, name,
                inputSummary, outputSummary, status, durationMs, result.errorCode());
        events.appendSystem(actor.tenantId(), runId, "tool.completed",
                Map.of("invocationId", invocationId, "toolName", name, "success", result.success(),
                        "errorCode", result.errorCode() == null ? "" : result.errorCode()));
        if (!result.success()) {
            errors.add(name + " 未完成（" + result.errorCode() + "）");
            return null;
        }
        try {
            return objectMapper.treeToValue(result.data(), resultType);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Tool returned an invalid result", invalid);
        }
    }

    private AgentPlan callModel(String userMessage) {
        var task = new FutureTask<>(() -> chatProvider.plan(userMessage));
        modelCallExecutor.execute(task);
        try {
            return task.get(20, TimeUnit.SECONDS);
        }
        catch (TimeoutException timedOut) {
            task.cancel(true);
            throw new ApiException("MODEL_TIMEOUT", "模型响应超时，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException("MODEL_CANCELLED", "模型请求已中断", HttpStatus.SERVICE_UNAVAILABLE);
        }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ApiException("MODEL_UNAVAILABLE", "当前无法完成问题识别", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private ToolResult callTool(ActorContext actor, UUID runId, String invocationId,
            String toolName, JsonNode parameters) {
        var task = new FutureTask<>(() -> tools.execute(actor, runId, invocationId, toolName, parameters));
        toolCallExecutor.execute(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        }
        catch (TimeoutException timedOut) {
            task.cancel(true);
            return ToolResult.failure("TOOL_TIMEOUT", true, actor.traceId());
        }
        catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResult.failure("TOOL_INTERRUPTED", true, actor.traceId());
        }
        catch (ExecutionException failed) {
            return ToolResult.failure("TOOL_FAILED", true, actor.traceId());
        }
    }

    private void ensureNotCancelled(ActorContext actor, UUID runId) {
        if ("CANCELLED".equals(runs.status(actor.tenantId(), runId).orElse(""))) {
            throw new RunCancelledException();
        }
    }

    private void complete(ActorContext actor, UUID conversationId, UUID runId,
            AnswerAssembler.AssembledAnswer assembled, PolicyEvidence policy, List<DraftView> draftViews) {
        transactionTemplate.executeWithoutResult(status -> {
            if (!"RUNNING".equals(runs.lockStatus(actor.tenantId(), runId).orElse(""))) {
                return;
            }
            var readyDrafts = draftViews.stream().map(draft -> drafts.markReady(actor, draft.id())).toList();
            var citationIds = new LinkedHashMap<UUID, UUID>();
            for (var citation : assembled.answer().citations()) {
                citationIds.put(citation.chunkId(), UUID.randomUUID());
            }
            JsonNode structured = structuredAnswer(assembled, policy, readyDrafts, citationIds);
            UUID messageId = runs.insertAssistantMessage(actor.tenantId(), conversationId, runId,
                    assembled.answer().answer(), json(structured));
            persistCitations(actor.tenantId(), runId, messageId, assembled, policy, citationIds);

            boolean waiting = !readyDrafts.isEmpty();
            if (runs.completeRun(actor.tenantId(), runId, waiting ? "WAITING_CONFIRMATION" : "COMPLETED") != 1) {
                throw new IllegalStateException("Agent run stopped before completion");
            }
            for (var citation : assembled.answer().citations()) {
                var chunk = findChunk(policy, citation.chunkId());
                events.appendSystem(actor.tenantId(), runId, "citation", Map.of("citationId", citationIds.get(chunk.chunkId()),
                        "chunkId", chunk.chunkId(), "documentTitle", chunk.documentTitle(),
                        "version", chunk.versionNo(), "page", chunk.pageNo() == null ? 0 : chunk.pageNo()));
            }
            for (var draft : readyDrafts) {
                events.appendSystem(actor.tenantId(), runId, "draft.created", Map.of(
                        "draftId", draft.id(), "type", draft.type(), "status", draft.status()));
            }
            events.appendSystem(actor.tenantId(), runId, "answer.delta", Map.of("text", assembled.answer().answer()));
            if (!waiting) {
                events.appendSystem(actor.tenantId(), runId, "run.completed", Map.of("runId", runId));
            }
            events.appendSystem(actor.tenantId(), runId, "answer.final", Map.of(
                    "answer", assembled.answer().answer(),
                    "citationIds", List.copyOf(citationIds.values()),
                    "draftIds", readyDrafts.stream().map(DraftView::id).toList(),
                    "needsHuman", waiting));
        });
    }

    private JsonNode structuredAnswer(AnswerAssembler.AssembledAnswer assembled,
            PolicyEvidence policy, List<DraftView> readyDrafts, Map<UUID, UUID> citationIds) {
        var root = objectMapper.createObjectNode();
        root.put("answer", assembled.answer().answer());
        root.put("needsHuman", assembled.needsHuman());
        var citations = root.putArray("citations");
        for (var citation : assembled.answer().citations()) {
            var chunk = findChunk(policy, citation.chunkId());
            var item = citations.addObject();
            item.put("citationId", citationIds.get(citation.chunkId()).toString());
            item.put("chunkId", citation.chunkId().toString());
            item.put("documentTitle", chunk.documentTitle());
            item.put("versionNo", chunk.versionNo());
            if (chunk.pageNo() != null) {
                item.put("pageNo", chunk.pageNo());
            }
            item.put("quote", citation.quote());
        }
        var suggestions = root.putArray("suggestedActions");
        for (var action : assembled.suggestedActions()) {
            var item = suggestions.addObject();
            item.put("draftId", action.draftId().toString());
            item.put("type", action.type());
            item.put("summary", action.summary());
        }
        root.putArray("draftIds").addAll(readyDrafts.stream()
                .map(draft -> objectMapper.getNodeFactory().textNode(draft.id().toString())).toList());
        return root;
    }

    private void persistCitations(UUID tenantId, UUID runId, UUID messageId,
            AnswerAssembler.AssembledAnswer assembled, PolicyEvidence policy, Map<UUID, UUID> citationIds) {
        for (var citation : assembled.answer().citations()) {
            var chunk = findChunk(policy, citation.chunkId());
            UUID citationId = citationIds.get(chunk.chunkId());
            var snapshot = objectMapper.createObjectNode();
            snapshot.put("chunkId", chunk.chunkId().toString());
            snapshot.put("documentTitle", chunk.documentTitle());
            snapshot.put("versionNo", chunk.versionNo());
            if (chunk.pageNo() != null) {
                snapshot.put("pageNo", chunk.pageNo());
            }
            snapshot.put("quote", citation.quote());
            runs.insertCitation(citationId, tenantId, runId, messageId, chunk.chunkId(), json(snapshot));
        }
    }

    private static PolicyEvidence.Chunk findChunk(PolicyEvidence policy, UUID id) {
        return policy.chunks().stream().filter(chunk -> chunk.chunkId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Validated citation chunk disappeared"));
    }

    private AgentPlan sanitize(AgentPlan plan) {
        if (plan == null) {
            return new AgentPlan(List.of(AgentPlan.Intent.UNKNOWN), null, null, null, null,
                    null, null, null, "售后问题", "", "NORMAL", null, null);
        }
        var intents = plan.intents().stream().distinct().filter(intent -> intent != null).toList();
        String title = bounded(plan.title(), 120);
        String description = bounded(plan.description(), 5000);
        return new AgentPlan(intents, bounded(plan.orderNo(), 64), bounded(plan.skuCode(), 64),
                plan.skuId(), plan.targetWarehouseId(), plan.quantity(), bounded(plan.categoryCode(), 64),
                plan.refundAmountCents(), title == null || title.isBlank() ? "售后问题" : title,
                description == null ? "" : description,
                "HIGH".equals(plan.priority()) || "URGENT".equals(plan.priority()) ? plan.priority() : "NORMAL",
                bounded(plan.refundReason(), 500), plan.sourceWarehouseId());
    }

    private void fail(ActorContext actor, UUID runId, String errorCode) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (runs.failRun(actor.tenantId(), runId, errorCode) == 1) {
                    drafts.invalidateRun(actor, runId);
                    events.appendSystem(actor.tenantId(), runId, "run.failed", Map.of("errorCode", errorCode));
                }
            });
        }
        catch (RuntimeException ignored) {
            // Keep the original run failure as the visible outcome; failure handling must not leak details.
        }
    }

    private static String errorCode(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RunCancelledException) {
                return "RUN_CANCELLED";
            }
            if (cause instanceof AgentBudget.BudgetExceededException budget) {
                return budget.code();
            }
            if (cause instanceof ApiException api) {
                return api.code();
            }
        }
        return "AGENT_RUN_FAILED";
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private JsonNode parameters(Map<String, ?> values) {
        return objectMapper.valueToTree(values);
    }

    private List<String> fieldNames(JsonNode parameters) {
        var names = new ArrayList<String>();
        parameters.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize run data", invalid);
        }
    }

    private static final class Holder<T> {
        private T value;
    }

    private static final class RunCancelledException extends RuntimeException {
    }

    private static final class SetOfOrderDependent {
        private static final java.util.Set<AgentPlan.Intent> contains = java.util.Set.of(
                AgentPlan.Intent.ORDER_QUERY, AgentPlan.Intent.LOGISTICS, AgentPlan.Intent.POLICY_QA,
                AgentPlan.Intent.TICKET_DRAFT, AgentPlan.Intent.REFUND_DRAFT);

        private static boolean contains(AgentPlan.Intent intent) {
            return contains.contains(intent);
        }
    }
}
