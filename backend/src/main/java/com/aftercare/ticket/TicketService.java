package com.aftercare.ticket;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aftercare.audit.AuditService;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
    private static final Set<Role> INTERNAL_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.WAREHOUSE_OPERATOR, Role.ADMIN);
    private static final Set<Role> TICKET_WORK_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.ADMIN);
    private static final Set<String> ASSIGNABLE_ROLES = Set.of("CUSTOMER_SERVICE", "SERVICE_SUPERVISOR", "ADMIN");

    private final TicketRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TicketService(TicketRepository repository, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public TicketView createFromDraft(ActorContext actor, UUID draftId, UUID sourceRunId, UUID orderId,
            String category, String title, String description, String priority, JsonNode evidenceSnapshot) {
        requireTicketWorkRole(actor);
        validatePayload(category, title, description, priority);
        UUID ticketId = UUID.randomUUID();
        String ticketNo = "TK-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString().replace("-", "")
                + "-" + ticketId.toString().replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        repository.insert(actor.tenantId(), ticketId, ticketNo, draftId, sourceRunId, orderId,
                category.trim(), title.trim(), description.trim(), priority,
                json(evidenceSnapshot), actor.actorId());
        repository.insertEvent(actor.tenantId(), ticketId, actor.actorId(), "CREATED", null,
                TicketStatus.OPEN, null);
        auditService.success(actor, "TICKET_CREATED", "TICKET", ticketId,
                Map.of("ticketNo", ticketNo, "category", category.trim(), "priority", priority,
                        "draftId", draftId));
        return get(actor, ticketId);
    }

    public TicketView get(ActorContext actor, UUID ticketId) {
        requireInternalRole(actor);
        var ticket = repository.find(actor.tenantId(), ticketId).orElseThrow(TicketService::notFound);
        return view(actor.tenantId(), ticket, true);
    }

    public TicketPage list(ActorContext actor, TicketStatus status, UUID orderId, int page, int size) {
        requireInternalRole(actor);
        if (page < 1 || size < 1 || size > 100) {
            throw new ApiException("INVALID_ARGUMENT", "page 从 1 开始，size 范围为 1–100", HttpStatus.BAD_REQUEST);
        }
        var rows = repository.list(actor.tenantId(), status, orderId, size, (page - 1) * size).stream()
                .map(record -> view(actor.tenantId(), record, false)).toList();
        return new TicketPage(rows, page, size, repository.count(actor.tenantId(), status, orderId));
    }

    @Transactional
    public TicketView assign(ActorContext actor, UUID ticketId, UUID assigneeId, long expectedVersion) {
        requireTicketWorkRole(actor);
        var ticket = repository.findForUpdate(actor.tenantId(), ticketId).orElseThrow(TicketService::notFound);
        if (expectedVersion != ticket.version()) {
            throw versionConflict();
        }
        if (actor.role() == Role.CUSTOMER_SERVICE && !actor.actorId().equals(assigneeId)) {
            throw new ApiException("FORBIDDEN", "客服只能领取给自己处理", HttpStatus.FORBIDDEN);
        }
        if (actor.role() != Role.CUSTOMER_SERVICE
                && actor.role() != Role.SERVICE_SUPERVISOR && actor.role() != Role.ADMIN) {
            throw new ApiException("FORBIDDEN", "当前账号不能分派工单", HttpStatus.FORBIDDEN);
        }
        var assignee = repository.findAssignableUser(actor.tenantId(), assigneeId)
                .orElseThrow(TicketService::notFound);
        if (!"ACTIVE".equals(assignee.status()) || !ASSIGNABLE_ROLES.contains(assignee.role())) {
            throw new ApiException("INVALID_ASSIGNEE", "只能分派给有效的客服或主管账号", HttpStatus.BAD_REQUEST);
        }
        TicketStateMachine.transition(ticket.status(), TicketStatus.ASSIGNED, null, false);
        if (repository.assign(actor.tenantId(), ticketId, assigneeId, expectedVersion) != 1) {
            throw versionConflict();
        }
        repository.insertEvent(actor.tenantId(), ticketId, actor.actorId(), "ASSIGNED",
                ticket.status(), TicketStatus.ASSIGNED, null);
        auditService.success(actor, "TICKET_ASSIGNED", "TICKET", ticketId,
                Map.of("assigneeId", assigneeId, "expectedVersion", expectedVersion));
        return get(actor, ticketId);
    }

    @Transactional
    public TicketView transition(ActorContext actor, UUID ticketId, TicketStatus target,
            String comment, long expectedVersion) {
        requireTicketWorkRole(actor);
        if (target == null || (comment != null && comment.length() > 5000)) {
            throw new ApiException("INVALID_ARGUMENT", "请提供有效的目标状态和处理备注", HttpStatus.BAD_REQUEST);
        }
        if (target == TicketStatus.ASSIGNED) {
            throw new ApiException("ASSIGNMENT_REQUIRED", "请使用分派接口同时指定处理人", HttpStatus.CONFLICT);
        }
        var ticket = repository.findForUpdate(actor.tenantId(), ticketId).orElseThrow(TicketService::notFound);
        if (actor.role() == Role.CUSTOMER_SERVICE
                && (ticket.assignedTo() == null || !ticket.assignedTo().equals(actor.actorId()))) {
            throw new ApiException("FORBIDDEN", "客服只能处理分派给自己的工单", HttpStatus.FORBIDDEN);
        }
        if (ticket.version() != expectedVersion) {
            throw versionConflict();
        }
        boolean pendingAction = repository.hasPendingSensitiveAction(actor.tenantId(), ticketId);
        if (target == TicketStatus.WAITING_APPROVAL && !pendingAction) {
            throw new ApiException("NO_PENDING_ACTION", "没有待审批申请，不能将工单转入等待审批", HttpStatus.CONFLICT);
        }
        TicketStateMachine.transition(ticket.status(), target,
                target == TicketStatus.RESOLVED ? comment : null, pendingAction);
        String resolution = target == TicketStatus.RESOLVED ? comment.trim() : null;
        if (repository.transition(actor.tenantId(), ticketId, target, resolution, expectedVersion) != 1) {
            throw versionConflict();
        }
        repository.insertEvent(actor.tenantId(), ticketId, actor.actorId(), "STATUS_CHANGED",
                ticket.status(), target, comment);
        auditService.success(actor, "TICKET_STATUS_CHANGED", "TICKET", ticketId,
                Map.of("fromStatus", ticket.status().name(), "toStatus", target.name(),
                        "expectedVersion", expectedVersion));
        return get(actor, ticketId);
    }

    private TicketView view(UUID tenantId, TicketRepository.TicketRecord row, boolean includeEvents) {
        return new TicketView(row.id(), row.ticketNo(), row.draftId(), row.sourceRunId(), row.orderId(),
                row.category(), row.title(), row.description(), row.priority(), row.status(),
                readJson(row.evidenceJson()), row.createdBy(), row.assignedTo(), row.resolution(), row.version(),
                row.createdAt(), row.updatedAt(), includeEvents ? repository.events(tenantId, row.id()) : List.of());
    }

    private static void validatePayload(String category, String title, String description, String priority) {
        if (category == null || category.isBlank() || category.trim().length() > 64
                || title == null || title.isBlank() || title.trim().length() > 120
                || description == null || description.isBlank() || description.trim().length() > 5000
                || priority == null || !Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority)) {
            throw new ApiException("INVALID_DRAFT", "草稿中的工单内容无效", HttpStatus.CONFLICT);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value == null ? objectMapper.createObjectNode() : value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Could not serialize ticket evidence", invalid);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored ticket evidence is invalid", invalid);
        }
    }

    private static void requireInternalRole(ActorContext actor) {
        if (!INTERNAL_ROLES.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能查看工单", HttpStatus.FORBIDDEN);
        }
    }

    private static void requireTicketWorkRole(ActorContext actor) {
        if (!TICKET_WORK_ROLES.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能处理工单", HttpStatus.FORBIDDEN);
        }
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该工单", HttpStatus.NOT_FOUND);
    }

    private static ApiException versionConflict() {
        return new ApiException("VERSION_CONFLICT", "工单已被更新，请刷新后重试", HttpStatus.CONFLICT);
    }

    public record TicketPage(List<TicketView> items, int page, int size, int total) {
        public TicketPage {
            items = List.copyOf(items);
        }
    }
}
