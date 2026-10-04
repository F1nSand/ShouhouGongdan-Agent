package com.aftercare.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aftercare.audit.AuditService;
import com.aftercare.agent.RunActionResultPublisher;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.aftercare.order.OrderRepository;
import com.aftercare.ticket.TicketService;
import com.aftercare.ticket.TicketView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionDraftService {
    private static final Duration DRAFT_TTL = Duration.ofMinutes(30);
    private static final Set<Role> INTERNAL_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.WAREHOUSE_OPERATOR, Role.ADMIN);
    private static final Set<Role> MANUAL_DRAFT_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.ADMIN);

    private final ActionDraftRepository repository;
    private final CommandDedupService commandDedup;
    private final TicketService ticketService;
    private final SensitiveActionService sensitiveActionService;
    private final OrderRepository orderRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RunActionResultPublisher runActionResultPublisher;

    public ActionDraftService(ActionDraftRepository repository, CommandDedupService commandDedup,
            TicketService ticketService, SensitiveActionService sensitiveActionService,
            OrderRepository orderRepository, AuditService auditService,
            ObjectMapper objectMapper, Clock clock, RunActionResultPublisher runActionResultPublisher) {
        this.repository = repository;
        this.commandDedup = commandDedup;
        this.ticketService = ticketService;
        this.sensitiveActionService = sensitiveActionService;
        this.orderRepository = orderRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.runActionResultPublisher = runActionResultPublisher;
    }

    @Transactional
    public DraftView createDraft(ActorContext actor, UUID runId, DraftCommand command) {
        requireInternalRole(actor);
        if (runId == null || command == null || command.conversationId() == null
                || command.invocationId() == null || command.invocationId().isBlank()
                || command.invocationId().length() > 128 || command.type() == null
                || !Set.of("CREATE_TICKET", "REQUEST_REFUND", "REQUEST_STOCK_TRANSFER").contains(command.type())
                || command.payload() == null || !command.payload().isObject()
                || command.evidenceSnapshot() == null || !command.evidenceSnapshot().isObject()) {
            throw new ApiException("INVALID_ARGUMENT", "Agent 草稿参数无效", HttpStatus.BAD_REQUEST);
        }
        if (!repository.runBelongsToActor(actor.tenantId(), actor.actorId(), command.conversationId(), runId)) {
            throw notFound();
        }
        String payloadJson = json(command.payload());
        String evidenceJson = json(command.evidenceSnapshot());
        String payloadHash = hash(command.type() + "\n" + command.conversationId() + "\n"
                + payloadJson + "\n" + evidenceJson);
        var existing = repository.findAgent(actor.tenantId(), runId, command.invocationId());
        if (existing.isPresent()) {
            if (!payloadHash.equals(existing.get().payloadHash())) {
                throw new ApiException("IDEMPOTENCY_CONFLICT", "工具调用 ID 已用于不同的草稿", HttpStatus.CONFLICT);
            }
            return view(existing.get());
        }

        int inserted = repository.insertAgent(actor.tenantId(), UUID.randomUUID(), runId, command.invocationId(),
                command.conversationId(), actor.actorId(), command.type(), payloadJson, payloadHash,
                evidenceJson, clock.instant().plus(DRAFT_TTL));
        var created = repository.findAgent(actor.tenantId(), runId, command.invocationId())
                .orElseThrow(() -> new IllegalStateException("Agent draft was not persisted"));
        if (!payloadHash.equals(created.payloadHash())) {
            throw new ApiException("IDEMPOTENCY_CONFLICT", "工具调用 ID 已用于不同的草稿", HttpStatus.CONFLICT);
        }
        if (inserted == 1) {
            auditService.success(actor, "ACTION_DRAFT_CREATED", "ACTION_DRAFT", created.id(),
                    Map.of("origin", "AGENT", "type", created.type(), "payloadHash", payloadHash));
        }
        return view(created);
    }

    @Transactional
    public DraftView createManualDraft(ActorContext actor, ManualTicketDraftCommand command,
            String idempotencyKey) {
        requireManualDraftRole(actor);
        var normalized = normalize(command);
        commandDedup.validateKey(idempotencyKey);
        if (normalized.orderId() != null
                && orderRepository.findById(actor.tenantId(), normalized.orderId()).isEmpty()) {
            throw notFound();
        }
        var replay = commandDedup.beginOrReplay(actor, "CREATE_MANUAL_DRAFT", idempotencyKey, normalized);
        if (replay.isPresent()) {
            return read(replay.get(), DraftView.class);
        }

        JsonNode payload = payload(normalized);
        UUID draftId = UUID.randomUUID();
        var expiresAt = clock.instant().plus(DRAFT_TTL);
        repository.insertManual(actor.tenantId(), draftId, actor.actorId(), json(payload), hash(json(payload)), expiresAt);
        var view = new DraftView(draftId, "MANUAL", null, "CREATE_TICKET", payload,
                objectMapper.createObjectNode(), "READY", expiresAt);
        commandDedup.complete(actor, "CREATE_MANUAL_DRAFT", idempotencyKey, view);
        auditService.success(actor, "ACTION_DRAFT_CREATED", "ACTION_DRAFT", draftId,
                Map.of("origin", "MANUAL", "type", "CREATE_TICKET", "payloadHash", hash(json(payload))));
        return view;
    }

    public DraftView get(ActorContext actor, UUID draftId) {
        var draft = repository.find(actor.tenantId(), draftId).orElseThrow(ActionDraftService::notFound);
        if (!draft.createdBy().equals(actor.actorId())) {
            throw notFound();
        }
        return view(draft);
    }

    @Transactional
    public DraftView markReady(ActorContext actor, UUID draftId) {
        var draft = repository.findForUpdate(actor.tenantId(), draftId).orElseThrow(ActionDraftService::notFound);
        if (!draft.createdBy().equals(actor.actorId())) {
            throw notFound();
        }
        if ("READY".equals(draft.status())) {
            return view(draft);
        }
        if (!"PREPARING".equals(draft.status()) || repository.markReady(actor.tenantId(), draftId) != 1) {
            throw new ApiException("DRAFT_NOT_READY", "草稿当前状态不能完成校验", HttpStatus.CONFLICT);
        }
        auditService.success(actor, "ACTION_DRAFT_READY", "ACTION_DRAFT", draftId, Map.of());
        return view(repository.find(actor.tenantId(), draftId).orElseThrow(ActionDraftService::notFound));
    }

    @Transactional
    public DraftView invalidate(ActorContext actor, UUID draftId, String reason) {
        var draft = repository.findForUpdate(actor.tenantId(), draftId).orElseThrow(ActionDraftService::notFound);
        if (!draft.createdBy().equals(actor.actorId())) {
            throw notFound();
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new ApiException("INVALID_ARGUMENT", "请提供不超过 500 字的失效原因", HttpStatus.BAD_REQUEST);
        }
        if (repository.invalidate(actor.tenantId(), draftId) != 1) {
            throw new ApiException("DRAFT_NOT_INVALIDATABLE", "草稿当前状态不能失效", HttpStatus.CONFLICT);
        }
        auditService.success(actor, "ACTION_DRAFT_INVALIDATED", "ACTION_DRAFT", draftId,
                Map.of("reason", reason.trim()));
        return view(repository.find(actor.tenantId(), draftId).orElseThrow(ActionDraftService::notFound));
    }

    @Transactional
    public int invalidateRun(ActorContext actor, UUID runId) {
        if (runId == null || repository.runOwner(actor.tenantId(), actor.actorId(), runId).isEmpty()) {
            throw notFound();
        }
        int invalidated = repository.invalidateRunDrafts(actor.tenantId(), runId);
        if (invalidated > 0) {
            auditService.success(actor, "AGENT_DRAFTS_INVALIDATED", "AGENT_RUN", runId,
                    Map.of("count", invalidated));
        }
        return invalidated;
    }

    @Transactional
    public ConfirmedActionResult confirm(ActorContext actor, UUID draftId, String idempotencyKey) {
        commandDedup.validateKey(idempotencyKey);
        var replay = commandDedup.beginOrReplay(actor, "CONFIRM_ACTION_DRAFT", idempotencyKey,
                Map.of("draftId", draftId));
        if (replay.isPresent()) {
            return read(replay.get(), ConfirmedActionResult.class);
        }

        var draft = repository.findForUpdate(actor.tenantId(), draftId).orElseThrow(ActionDraftService::notFound);
        if (!draft.createdBy().equals(actor.actorId())) {
            throw notFound();
        }
        if ("CONFIRMED".equals(draft.status())) {
            var original = read(draft.confirmedResultJson(), ConfirmedActionResult.class);
            commandDedup.complete(actor, "CONFIRM_ACTION_DRAFT", idempotencyKey, original);
            return original;
        }
        if ("PREPARING".equals(draft.status())) {
            throw new ApiException("DRAFT_NOT_READY", "草稿尚未完成校验", HttpStatus.CONFLICT);
        }
        if ("EXPIRED".equals(draft.status()) || !draft.expiresAt().isAfter(clock.instant())) {
            throw new ApiException("DRAFT_EXPIRED", "草稿已过期，请重新生成", HttpStatus.GONE);
        }
        if (!"READY".equals(draft.status())) {
            throw new ApiException("DRAFT_NOT_CONFIRMABLE", "草稿当前状态不能确认", HttpStatus.CONFLICT);
        }
        ConfirmedActionResult result;
        if ("CREATE_TICKET".equals(draft.type())) {
            JsonNode payload = read(draft.payloadJson(), JsonNode.class);
            JsonNode evidence = read(draft.evidenceJson(), JsonNode.class);
            TicketView ticket = ticketService.createFromDraft(actor, draft.id(), draft.runId(),
                    uuid(payload, "orderId"), required(payload, "category"), required(payload, "title"),
                    required(payload, "description"), required(payload, "priority"), evidence);
            result = new ConfirmedActionResult(draft.id(), ticket.id(), ticket.ticketNo(), ticket.status().name());
        }
        else if ("REQUEST_REFUND".equals(draft.type()) || "REQUEST_STOCK_TRANSFER".equals(draft.type())) {
            var action = sensitiveActionService.createFromDraft(actor, draft);
            result = new ConfirmedActionResult(draft.id(), action.ticketId(), null,
                    action.status(), action.id());
        }
        else {
            throw new ApiException("UNSUPPORTED_DRAFT_TYPE", "此草稿类型尚未开放确认", HttpStatus.CONFLICT);
        }
        repository.markConfirmed(actor.tenantId(), draft.id(), json(result));
        commandDedup.complete(actor, "CONFIRM_ACTION_DRAFT", idempotencyKey, result);
        Map<String, ?> confirmationSummary = result.actionId() == null
                ? Map.of("ticketId", result.ticketId(), "ticketNo", result.ticketNo())
                : Map.of("actionId", result.actionId(), "status", result.status());
        auditService.success(actor, "ACTION_DRAFT_CONFIRMED", "ACTION_DRAFT", draft.id(), confirmationSummary);
        if (draft.runId() != null) {
            runActionResultPublisher.confirmed(actor, draft.runId(), result);
        }
        return result;
    }

    private ManualTicketDraftCommand normalize(ManualTicketDraftCommand command) {
        if (command == null || blankOrLong(command.category(), 64) || blankOrLong(command.title(), 120)
                || blankOrLong(command.description(), 5000) || command.priority() == null
                || !Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(command.priority())) {
            throw new ApiException("INVALID_ARGUMENT", "请提供有效的工单类别、标题、问题描述和优先级",
                    HttpStatus.BAD_REQUEST);
        }
        return new ManualTicketDraftCommand(command.orderId(), command.category().trim(), command.title().trim(),
                command.description().trim(), command.priority());
    }

    private static boolean blankOrLong(String value, int maxLength) {
        return value == null || value.isBlank() || value.trim().length() > maxLength;
    }

    private JsonNode payload(ManualTicketDraftCommand command) {
        var payload = objectMapper.createObjectNode();
        if (command.orderId() != null) {
            payload.put("orderId", command.orderId().toString());
        }
        payload.put("category", command.category());
        payload.put("title", command.title());
        payload.put("description", command.description());
        payload.put("priority", command.priority());
        return payload;
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Could not serialize action draft", invalid);
        }
    }

    private DraftView view(ActionDraftRepository.DraftRecord draft) {
        return new DraftView(draft.id(), draft.origin(), draft.runId(), draft.type(),
                read(draft.payloadJson(), JsonNode.class), read(draft.evidenceJson(), JsonNode.class),
                draft.status(), draft.expiresAt());
    }

    private static UUID uuid(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : UUID.fromString(node.asText());
    }

    private static String required(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ApiException("INVALID_DRAFT", "草稿内容无效", HttpStatus.CONFLICT);
        }
        return node.asText();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize action draft", invalid);
        }
    }

    private <T> T read(JsonNode value, Class<T> type) {
        return objectMapper.convertValue(value, type);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored action draft data is invalid", invalid);
        }
    }

    private static void requireManualDraftRole(ActorContext actor) {
        if (!MANUAL_DRAFT_ROLES.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能创建人工工单草稿", HttpStatus.FORBIDDEN);
        }
    }

    private static void requireInternalRole(ActorContext actor) {
        if (!INTERNAL_ROLES.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能创建操作草稿", HttpStatus.FORBIDDEN);
        }
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该操作草稿", HttpStatus.NOT_FOUND);
    }
}
