package com.aftercare.approval;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aftercare.audit.AuditService;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {
    private static final Set<Role> REFUND_APPROVERS = Set.of(Role.SERVICE_SUPERVISOR, Role.ADMIN);
    private static final Set<Role> TRANSFER_APPROVERS = Set.of(Role.WAREHOUSE_OPERATOR, Role.ADMIN);
    private static final String COMMAND_NAME = "DECIDE_SENSITIVE_ACTION";

    private final SensitiveActionRepository repository;
    private final CommandDedupService commandDedup;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApprovalService(SensitiveActionRepository repository, CommandDedupService commandDedup,
            AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.commandDedup = commandDedup;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public DecisionResult decide(ActorContext actor, UUID actionId, Decision decision,
            String reason, long expectedVersion, String idempotencyKey) {
        if (decision == null || reason == null || reason.isBlank() || reason.trim().length() > 500
                || expectedVersion < 0) {
            throw new ApiException("INVALID_ARGUMENT", "请提供有效的审批决定、原因和版本", HttpStatus.BAD_REQUEST);
        }
        commandDedup.validateKey(idempotencyKey);
        var command = Map.of("actionId", actionId, "decision", decision.name(),
                "reason", reason.trim(), "expectedVersion", expectedVersion);
        var replay = commandDedup.beginOrReplay(actor, COMMAND_NAME, idempotencyKey, command);
        if (replay.isPresent()) {
            return objectMapper.convertValue(replay.get(), DecisionResult.class);
        }

        var action = repository.findForUpdate(actor.tenantId(), actionId)
                .orElseThrow(ApprovalService::notFound);
        requireApprover(actor, action.type());
        if (action.requestedBy().equals(actor.actorId())) {
            throw new ApiException("SELF_APPROVAL_FORBIDDEN", "申请人不能审批自己的申请", HttpStatus.FORBIDDEN);
        }
        if (expectedVersion != action.version()) {
            throw new ApiException("VERSION_CONFLICT", "申请已被其他操作更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (!"PENDING_APPROVAL".equals(action.status())) {
            throw new ApiException("APPROVAL_ALREADY_DECIDED", "申请已经处理或不再等待审批", HttpStatus.CONFLICT);
        }
        if (!action.expiresAt().isAfter(clock.instant())) {
            throw new ApiException("ACTION_EXPIRED", "申请已过期", HttpStatus.GONE);
        }

        String status = decision == Decision.APPROVE ? "APPROVED" : "REJECTED";
        if (repository.decide(actor.tenantId(), actionId, status, expectedVersion) != 1) {
            throw new ApiException("VERSION_CONFLICT", "申请已被其他操作更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        repository.insertDecision(actor.tenantId(), actionId, actor.actorId(), decision.name(),
                reason.trim(), action.payloadHash());
        if (decision == Decision.APPROVE) {
            repository.enqueueExecution(actor.tenantId(), actionId);
        }
        var result = new DecisionResult(actionId, status, expectedVersion + 1);
        commandDedup.complete(actor, COMMAND_NAME, idempotencyKey, result);
        auditService.success(actor, "SENSITIVE_ACTION_" + decision.name(), "SENSITIVE_ACTION", actionId,
                Map.of("type", action.type(), "payloadHash", action.payloadHash(),
                        "expectedVersion", expectedVersion, "reason", reason.trim()));
        return result;
    }

    private static void requireApprover(ActorContext actor, String actionType) {
        Set<Role> permitted = switch (actionType) {
            case "REFUND_REQUEST" -> REFUND_APPROVERS;
            case "STOCK_TRANSFER" -> TRANSFER_APPROVERS;
            default -> Set.of();
        };
        if (!permitted.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能审批此类申请", HttpStatus.FORBIDDEN);
        }
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该申请", HttpStatus.NOT_FOUND);
    }
}
