package com.aftercare.approval;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.aftercare.audit.AuditService;
import com.aftercare.common.ApiException;
import com.aftercare.inventory.TransferOrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Service
public class ActionExecutor {
    private final SensitiveActionRepository actions;
    private final RefundSimulationAdapter refunds;
    private final LocalDemoRefundAdapter localDemoRefunds;
    private final TransferOrderService transfers;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String refundMode;

    public ActionExecutor(SensitiveActionRepository actions, RefundSimulationAdapter refunds,
            LocalDemoRefundAdapter localDemoRefunds,
            TransferOrderService transfers, AuditService auditService, ObjectMapper objectMapper, Clock clock,
            @Value("${app.refund.mode:simulation}") String refundMode) {
        this.actions = actions;
        this.refunds = refunds;
        this.localDemoRefunds = localDemoRefunds;
        this.transfers = transfers;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.refundMode = refundMode;
    }

    @Transactional
    public ExecutionResult execute(UUID tenantId, UUID actionId) {
        var action = actions.findForUpdate(tenantId, actionId)
                .orElseThrow(ActionExecutor::notFound);
        if (isTerminal(action.status())) {
            return readResult(action);
        }
        if (!"APPROVED".equals(action.status()) && !"EXECUTING".equals(action.status())) {
            throw new ApiException("ACTION_NOT_APPROVED", "申请尚未批准", HttpStatus.CONFLICT);
        }
        if (!"EXECUTING".equals(action.status()) && !action.expiresAt().isAfter(clock.instant())) {
            actions.markExpired(tenantId, actionId);
            return new ExecutionResult(actionId, "EXPIRED", false, null, "ACTION_EXPIRED", null, null);
        }
        if ("APPROVED".equals(action.status()) && actions.markExecuting(tenantId, actionId) != 1) {
            throw new ApiException("ACTION_STATE_CONFLICT", "申请状态已变化", HttpStatus.CONFLICT);
        }

        if ("REFUND_REQUEST".equals(action.type())) {
            return executeRefund(action, read(action.payloadJson()));
        }
        if ("STOCK_TRANSFER".equals(action.type())) {
            try {
                var transfer = transfers.reserveForApprovedAction(tenantId, actionId);
                return new ExecutionResult(actionId, "SUCCEEDED", false, transfer.id(), null, null, null);
            }
            catch (ApiException failure) {
                var latest = actions.find(tenantId, actionId).orElseThrow(ActionExecutor::notFound);
                if ("FAILED".equals(latest.status())) {
                    return readResult(latest);
                }
                return markFailed(tenantId, actionId, failure.code());
            }
        }
        return markFailed(tenantId, actionId, "UNSUPPORTED_ACTION_TYPE");
    }

    private ExecutionResult executeRefund(SensitiveActionRepository.ActionRecord action, JsonNode payload) {
        try {
            if ("local-demo".equalsIgnoreCase(refundMode)) {
                JsonNode details = localDemoRefunds.record(action.tenantId(), action.id(), payload);
                var result = new ExecutionResult(action.id(), "SUCCEEDED", false, null, null,
                        "LOCAL_DEMO_LEDGER", details);
                if (actions.markSucceeded(action.tenantId(), action.id(), "SUCCEEDED", json(result)) != 1) {
                    throw new IllegalStateException("Refund action changed before local demo refund was recorded");
                }
                auditService.systemSuccess(action.tenantId(), "REFUND_LOCAL_DEMO_RECORDED", "SENSITIVE_ACTION",
                        action.id(), Map.of("payloadHash", action.payloadHash(), "result", details,
                                "externalPayment", false));
                return result;
            }
            JsonNode details = refunds.simulate(action.tenantId(), payload);
            var result = new ExecutionResult(action.id(), "SIMULATED_SUCCEEDED", true, null, null,
                    "SIMULATION", details);
            if (actions.markSucceeded(action.tenantId(), action.id(), "SIMULATED_SUCCEEDED", json(result)) != 1) {
                throw new IllegalStateException("Refund action changed before simulation was recorded");
            }
            auditService.systemSuccess(action.tenantId(), "REFUND_SIMULATED", "SENSITIVE_ACTION", action.id(),
                    Map.of("payloadHash", action.payloadHash(), "result", details, "externalPayment", false));
            return result;
        }
        catch (ApiException businessFailure) {
            return markFailed(action.tenantId(), action.id(), businessFailure.code());
        }
    }

    private ExecutionResult markFailed(UUID tenantId, UUID actionId, String errorCode) {
        var result = new ExecutionResult(actionId, "FAILED", false, null, errorCode, null, null);
        if (actions.markFailed(tenantId, actionId, errorCode, json(result)) == 1) {
            auditService.systemFailure(tenantId, "SENSITIVE_ACTION_EXECUTION_FAILED", "SENSITIVE_ACTION",
                    actionId, Map.of("errorCode", errorCode), errorCode);
        }
        return result;
    }

    private ExecutionResult readResult(SensitiveActionRepository.ActionRecord action) {
        if (action.executionJson() != null) {
            try {
                return objectMapper.readValue(action.executionJson(), ExecutionResult.class);
            }
            catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Stored action result is invalid", invalid);
            }
        }
        return new ExecutionResult(action.id(), action.status(), false, null, action.errorCode(), null, null);
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JsonProcessingException invalid) {
            throw new ApiException("INVALID_ACTION_PAYLOAD", "申请内容无效", HttpStatus.CONFLICT);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize execution result", invalid);
        }
    }

    private static boolean isTerminal(String status) {
        return SetHolder.TERMINAL.contains(status);
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该申请", HttpStatus.NOT_FOUND);
    }

    private static final class SetHolder {
        private static final java.util.Set<String> TERMINAL = java.util.Set.of(
                "SUCCEEDED", "SIMULATED_SUCCEEDED", "FAILED", "REJECTED", "EXPIRED");
    }
}
