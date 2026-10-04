package com.aftercare.agent;

import java.util.Set;
import java.util.UUID;

import com.aftercare.approval.ActionDraftService;
import com.aftercare.approval.DraftCommand;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.aftercare.inventory.InventoryService;
import com.aftercare.knowledge.PolicyEvidence;
import com.aftercare.knowledge.PolicyRetriever;
import com.aftercare.logistics.LogisticsService;
import com.aftercare.order.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ToolGateway {
    private static final Set<String> REGISTERED_TOOLS = Set.of(
            "query_order", "query_logistics", "retrieve_policy", "query_inventory",
            "recommend_transfer", "draft_ticket", "draft_sensitive_action");
    private static final Set<Role> INTERNAL_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.WAREHOUSE_OPERATOR, Role.ADMIN);

    private final OrderService orders;
    private final LogisticsService logistics;
    private final PolicyRetriever policies;
    private final InventoryService inventory;
    private final ActionDraftService drafts;
    private final AgentRunRepository runs;
    private final RunEvidenceRegistry evidenceRegistry;
    private final ObjectMapper objectMapper;

    public ToolGateway(OrderService orders, LogisticsService logistics, PolicyRetriever policies,
            InventoryService inventory, ActionDraftService drafts, AgentRunRepository runs,
            RunEvidenceRegistry evidenceRegistry, ObjectMapper objectMapper) {
        this.orders = orders;
        this.logistics = logistics;
        this.policies = policies;
        this.inventory = inventory;
        this.drafts = drafts;
        this.runs = runs;
        this.evidenceRegistry = evidenceRegistry;
        this.objectMapper = objectMapper;
    }

    public Set<String> registeredTools() {
        return REGISTERED_TOOLS;
    }

    public ToolResult execute(ActorContext actor, UUID runId, String invocationId,
            String toolName, JsonNode parameters) {
        if (actor == null || !INTERNAL_ROLES.contains(actor.role())) {
            return ToolResult.failure("FORBIDDEN", false, actor == null ? null : actor.traceId());
        }
        if (runId == null || invocationId == null || invocationId.isBlank() || invocationId.length() > 128
                || toolName == null || !REGISTERED_TOOLS.contains(toolName)) {
            return ToolResult.failure("TOOL_NOT_ALLOWED", false, actor.traceId());
        }
        if (parameters == null || !parameters.isObject()) {
            return ToolResult.failure("INVALID_ARGUMENT", false, actor.traceId());
        }
        var conversationId = runs.conversationForOwnedRun(actor.tenantId(), actor.actorId(), runId);
        if (conversationId.isEmpty()) {
            return ToolResult.failure("RESOURCE_NOT_FOUND", false, actor.traceId());
        }
        try {
            Object result = switch (toolName) {
                case "query_order" -> orders.query(actor, requiredText(parameters, "orderNo", 64));
                case "query_logistics" -> logistics.queryLogistics(actor, requiredUuid(parameters, "orderId"));
                case "retrieve_policy" -> retrievePolicy(actor, runId, parameters);
                case "query_inventory" -> inventory.queryInventory(actor, requiredText(parameters, "skuCode", 64));
                case "recommend_transfer" -> inventory.recommend(actor,
                        requiredUuid(parameters, "skuId"), requiredUuid(parameters, "targetWarehouseId"),
                        requiredPositiveInt(parameters, "quantity"));
                case "draft_ticket" -> draftTicket(actor, runId, invocationId,
                        conversationId.get(), parameters);
                case "draft_sensitive_action" -> draftSensitiveAction(actor, runId, invocationId,
                        conversationId.get(), parameters);
                default -> throw new IllegalStateException("Unregistered tool passed dispatch");
            };
            return ToolResult.success(objectMapper.valueToTree(result), actor.traceId());
        }
        catch (ApiException known) {
            return ToolResult.failure(known.code(), known.status().is5xxServerError(), actor.traceId());
        }
        catch (IllegalArgumentException invalid) {
            return ToolResult.failure("INVALID_ARGUMENT", false, actor.traceId());
        }
        catch (RuntimeException failure) {
            return ToolResult.failure("TOOL_FAILED", true, actor.traceId());
        }
    }

    private PolicyEvidence retrievePolicy(ActorContext actor, UUID runId, JsonNode parameters) {
        String query = requiredText(parameters, "query", 2000);
        UUID orderId = optionalUuid(parameters, "orderId");
        String categoryCode = optionalText(parameters, "categoryCode", 64);
        PolicyEvidence found = policies.retrieve(actor, query, orderId, categoryCode);
        evidenceRegistry.record(runId, found);
        return found;
    }

    private Object draftTicket(ActorContext actor, UUID runId, String invocationId,
            UUID conversationId, JsonNode parameters) {
        var payload = objectMapper.createObjectNode();
        putIfPresent(payload, "orderId", optionalUuid(parameters, "orderId"));
        payload.put("category", requiredText(parameters, "category", 64));
        payload.put("title", requiredText(parameters, "title", 120));
        payload.put("description", requiredText(parameters, "description", 5000));
        payload.put("priority", requiredText(parameters, "priority", 16));
        return drafts.createDraft(actor, runId, new DraftCommand(invocationId, conversationId,
                "CREATE_TICKET", payload, evidenceSnapshot(runId)));
    }

    private Object draftSensitiveAction(ActorContext actor, UUID runId, String invocationId,
            UUID conversationId, JsonNode parameters) {
        String requestedType = requiredText(parameters, "type", 32);
        String draftType = switch (requestedType) {
            case "REFUND_REQUEST" -> "REQUEST_REFUND";
            case "STOCK_TRANSFER" -> "REQUEST_STOCK_TRANSFER";
            default -> throw new IllegalArgumentException("Unsupported sensitive draft type");
        };
        JsonNode typedPayload = parameters.get("typedPayload");
        if (typedPayload == null || !typedPayload.isObject()) {
            throw new IllegalArgumentException("typedPayload must be an object");
        }
        var payload = objectMapper.createObjectNode();
        putIfPresent(payload, "ticketId", optionalUuid(typedPayload, "ticketId"));
        if ("REQUEST_REFUND".equals(draftType)) {
            payload.set("orderId", requiredUuidNode(typedPayload, "orderId"));
            payload.put("amountCents", requiredPositiveLong(typedPayload, "amountCents"));
            payload.put("reason", requiredText(typedPayload, "reason", 500));
        }
        else {
            payload.set("skuId", requiredUuidNode(typedPayload, "skuId"));
            payload.set("sourceWarehouseId", requiredUuidNode(typedPayload, "sourceWarehouseId"));
            payload.set("targetWarehouseId", requiredUuidNode(typedPayload, "targetWarehouseId"));
            payload.put("quantity", requiredPositiveInt(typedPayload, "quantity"));
        }
        return drafts.createDraft(actor, runId, new DraftCommand(invocationId, conversationId,
                draftType, payload, evidenceSnapshot(runId)));
    }

    private JsonNode evidenceSnapshot(UUID runId) {
        var snapshot = objectMapper.createObjectNode();
        var chunks = snapshot.putArray("chunks");
        for (var chunk : evidenceRegistry.snapshot(runId).chunks()) {
            var item = chunks.addObject();
            item.put("chunkId", chunk.chunkId().toString());
            item.put("documentTitle", chunk.documentTitle());
            item.put("versionNo", chunk.versionNo());
            if (chunk.pageNo() != null) {
                item.put("pageNo", chunk.pageNo());
            }
            item.put("content", chunk.content());
        }
        return snapshot;
    }

    private static String requiredText(JsonNode value, String field, int maxLength) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()
                || node.asText().trim().length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return node.asText().trim();
    }

    private static String optionalText(JsonNode value, String field, int maxLength) {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return requiredText(value, field, maxLength);
    }

    private static UUID requiredUuid(JsonNode value, String field) {
        try {
            return UUID.fromString(requiredText(value, field, 36));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid UUID field " + field);
        }
    }

    private static JsonNode requiredUuidNode(JsonNode value, String field) {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(requiredUuid(value, field).toString());
    }

    private static UUID optionalUuid(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid UUID field " + field);
        }
    }

    private static int requiredPositiveInt(JsonNode value, String field) {
        long number = requiredPositiveLong(value, field);
        return Math.toIntExact(number);
    }

    private static long requiredPositiveLong(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.canConvertToLong() || node.asLong() <= 0) {
            throw new IllegalArgumentException("Invalid numeric field " + field);
        }
        return node.asLong();
    }

    private static void putIfPresent(com.fasterxml.jackson.databind.node.ObjectNode target,
            String field, UUID value) {
        if (value != null) {
            target.put(field, value.toString());
        }
    }
}
