package com.aftercare.approval;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aftercare.audit.AuditService;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.aftercare.order.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SensitiveActionService {
    private static final Duration ACTION_TTL = Duration.ofHours(24);
    private static final Set<Role> REFUND_REQUEST_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.ADMIN);
    private static final Set<Role> TRANSFER_REQUEST_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.WAREHOUSE_OPERATOR, Role.ADMIN);

    private final SensitiveActionRepository repository;
    private final OrderRepository orderRepository;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SensitiveActionService(SensitiveActionRepository repository, OrderRepository orderRepository,
            JdbcTemplate jdbc, AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public SensitiveActionRepository.ActionRecord createFromDraft(ActorContext actor,
            ActionDraftRepository.DraftRecord draft) {
        if (!actor.actorId().equals(draft.createdBy())) {
            throw new ApiException("FORBIDDEN", "当前账号不能提交此申请", HttpStatus.FORBIDDEN);
        }
        JsonNode payload = read(draft.payloadJson());
        String type = switch (draft.type()) {
            case "REQUEST_REFUND" -> {
                if (!REFUND_REQUEST_ROLES.contains(actor.role())) {
                    throw new ApiException("FORBIDDEN", "当前账号不能提交退款申请", HttpStatus.FORBIDDEN);
                }
                yield "REFUND_REQUEST";
            }
            case "REQUEST_STOCK_TRANSFER" -> {
                if (!TRANSFER_REQUEST_ROLES.contains(actor.role())) {
                    throw new ApiException("FORBIDDEN", "当前账号不能提交调拨申请", HttpStatus.FORBIDDEN);
                }
                yield "STOCK_TRANSFER";
            }
            default -> throw invalidDraft();
        };
        UUID ticketId = optionalUuid(payload, "ticketId");
        if (ticketId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tickets WHERE tenant_id = ? AND id = ?)
                """, Boolean.class, actor.tenantId(), ticketId))) {
            throw notFound();
        }
        if ("REFUND_REQUEST".equals(type)) {
            validateRefund(actor.tenantId(), payload);
        }
        else {
            validateTransfer(actor.tenantId(), payload);
        }

        UUID actionId = UUID.randomUUID();
        repository.insert(actor.tenantId(), actionId, draft.id(), ticketId, actor.actorId(), type,
                draft.payloadJson(), draft.payloadHash(), draft.evidenceJson(), clock.instant().plus(ACTION_TTL));
        auditService.success(actor, "SENSITIVE_ACTION_REQUESTED", "SENSITIVE_ACTION", actionId,
                Map.of("type", type, "draftId", draft.id(), "payloadHash", draft.payloadHash()));
        return repository.find(actor.tenantId(), actionId).orElseThrow(SensitiveActionService::notFound);
    }

    public List<SensitiveActionView> list(ActorContext actor, int page, int size) {
        requirePage(page, size);
        int offset = (page - 1) * size;
        var records = switch (actor.role()) {
            case ADMIN -> repository.listAll(actor.tenantId(), size, offset);
            case SERVICE_SUPERVISOR -> merge(repository.listOwned(actor.tenantId(), actor.actorId(), size, offset),
                    repository.listApprovable(actor.tenantId(), "REFUND_REQUEST", size, offset));
            case WAREHOUSE_OPERATOR -> merge(repository.listOwned(actor.tenantId(), actor.actorId(), size, offset),
                    repository.listApprovable(actor.tenantId(), "STOCK_TRANSFER", size, offset));
            default -> repository.listOwned(actor.tenantId(), actor.actorId(), size, offset);
        };
        return records.stream().distinct().map(this::view).toList();
    }

    public SensitiveActionView get(ActorContext actor, UUID actionId) {
        var record = repository.find(actor.tenantId(), actionId).orElseThrow(SensitiveActionService::notFound);
        if (!canView(actor, record)) {
            throw notFound();
        }
        return view(record);
    }

    @Transactional
    public int expire(java.time.Instant now) {
        int changed = 0;
        for (var action : repository.findExpiredForUpdate(now)) {
            if (repository.markExpired(action.tenantId(), action.id()) == 1) {
                auditService.systemSuccess(action.tenantId(), "SENSITIVE_ACTION_EXPIRED",
                        "SENSITIVE_ACTION", action.id(), Map.of("previousStatus", action.status()));
                changed++;
            }
        }
        return changed;
    }

    @Transactional
    public void markExecutionFailed(UUID tenantId, UUID actionId, String errorCode) {
        var result = new ExecutionResult(actionId, "FAILED", false, null, errorCode, null, null);
        if (repository.markFailed(tenantId, actionId, errorCode, json(result)) == 1) {
            auditService.systemFailure(tenantId, "SENSITIVE_ACTION_EXECUTION_FAILED", "SENSITIVE_ACTION",
                    actionId, Map.of("errorCode", errorCode), errorCode);
        }
    }

    private void validateRefund(UUID tenantId, JsonNode payload) {
        UUID orderId = requiredUuid(payload, "orderId");
        long amount = requiredLong(payload, "amountCents");
        String reason = requiredText(payload, "reason", 500);
        var order = orderRepository.findById(tenantId, orderId).orElseThrow(SensitiveActionService::notFound);
        long remaining = order.paidAmountCents() - order.refundedAmountCents();
        if (amount <= 0 || amount > remaining || reason.isBlank()) {
            throw new ApiException("REFUND_AMOUNT_INVALID", "退款金额必须大于 0 且不超过未退款金额", HttpStatus.CONFLICT);
        }
    }

    private void validateTransfer(UUID tenantId, JsonNode payload) {
        UUID skuId = requiredUuid(payload, "skuId");
        UUID sourceId = requiredUuid(payload, "sourceWarehouseId");
        UUID targetId = requiredUuid(payload, "targetWarehouseId");
        int quantity = Math.toIntExact(requiredLong(payload, "quantity"));
        if (quantity <= 0 || sourceId.equals(targetId)) {
            throw invalidDraft();
        }
        boolean valid = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM skus s
                    JOIN warehouses src ON src.tenant_id = s.tenant_id AND src.id = ? AND src.status = 'ACTIVE'
                    JOIN warehouses dst ON dst.tenant_id = s.tenant_id AND dst.id = ? AND dst.status = 'ACTIVE'
                    JOIN warehouse_routes route ON route.tenant_id = s.tenant_id
                        AND route.source_warehouse_id = src.id AND route.target_warehouse_id = dst.id
                    JOIN inventory i ON i.tenant_id = s.tenant_id AND i.sku_id = s.id AND i.warehouse_id = src.id
                    WHERE s.tenant_id = ? AND s.id = ? AND s.status = 'ACTIVE'
                      AND i.on_hand - i.reserved - i.safety_stock >= ?
                )
                """, Boolean.class, sourceId, targetId, tenantId, skuId, quantity));
        if (!valid) {
            throw new ApiException("TRANSFER_NOT_AVAILABLE", "当前库存或仓间路线不满足调拨申请", HttpStatus.CONFLICT);
        }
    }

    private boolean canView(ActorContext actor, SensitiveActionRepository.ActionRecord record) {
        if (record.requestedBy().equals(actor.actorId()) || actor.role() == Role.ADMIN) {
            return true;
        }
        return "REFUND_REQUEST".equals(record.type()) && actor.role() == Role.SERVICE_SUPERVISOR
                || "STOCK_TRANSFER".equals(record.type()) && actor.role() == Role.WAREHOUSE_OPERATOR;
    }

    private SensitiveActionView view(SensitiveActionRepository.ActionRecord record) {
        return new SensitiveActionView(record.id(), record.ticketId(), record.type(), read(record.payloadJson()),
                read(record.evidenceJson()), record.status(), record.expiresAt(), record.version(),
                record.requestedBy(), readNullable(record.executionJson()), record.errorCode());
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored sensitive action JSON is invalid", invalid);
        }
    }

    private JsonNode readNullable(String json) {
        return json == null ? null : read(json);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize execution result", invalid);
        }
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        UUID value = optionalUuid(node, field);
        if (value == null) {
            throw invalidDraft();
        }
        return value;
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        }
        catch (IllegalArgumentException invalid) {
            throw invalidDraft();
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw invalidDraft();
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().trim().length() > maxLength) {
            throw invalidDraft();
        }
        return value.asText().trim();
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ApiException("INVALID_ARGUMENT", "page 从 1 开始，size 范围为 1–100", HttpStatus.BAD_REQUEST);
        }
    }

    private static List<SensitiveActionRepository.ActionRecord> merge(
            List<SensitiveActionRepository.ActionRecord> first,
            List<SensitiveActionRepository.ActionRecord> second) {
        var merged = new ArrayList<SensitiveActionRepository.ActionRecord>(first);
        merged.addAll(second);
        return merged.stream().distinct().toList();
    }

    private static ApiException invalidDraft() {
        return new ApiException("INVALID_DRAFT", "敏感操作草稿内容无效", HttpStatus.CONFLICT);
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该申请", HttpStatus.NOT_FOUND);
    }
}
