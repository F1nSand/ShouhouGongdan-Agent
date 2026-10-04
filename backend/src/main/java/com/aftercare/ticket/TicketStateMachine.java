package com.aftercare.ticket;

import java.util.Map;
import java.util.Set;

import com.aftercare.common.ApiException;
import org.springframework.http.HttpStatus;

public final class TicketStateMachine {
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = Map.of(
            TicketStatus.OPEN, Set.of(TicketStatus.ASSIGNED),
            TicketStatus.ASSIGNED, Set.of(TicketStatus.IN_PROGRESS),
            TicketStatus.IN_PROGRESS, Set.of(TicketStatus.WAITING_APPROVAL, TicketStatus.RESOLVED),
            TicketStatus.WAITING_APPROVAL, Set.of(TicketStatus.IN_PROGRESS),
            TicketStatus.RESOLVED, Set.of(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS),
            TicketStatus.CLOSED, Set.of());

    private TicketStateMachine() {
    }

    public static TicketStatus transition(TicketStatus current, TicketStatus target, String resolution,
            boolean hasPendingSensitiveAction) {
        if (current == TicketStatus.IN_PROGRESS && target == TicketStatus.RESOLVED
                && (resolution == null || resolution.isBlank())) {
            throw new ApiException("RESOLUTION_REQUIRED", "解决工单前请填写处理摘要", HttpStatus.BAD_REQUEST);
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new ApiException("INVALID_TRANSITION", "工单当前状态不允许此操作", HttpStatus.CONFLICT);
        }
        if (hasPendingSensitiveAction && (target == TicketStatus.RESOLVED || target == TicketStatus.CLOSED)) {
            throw new ApiException("ACTION_PENDING", "仍有未完成的敏感操作申请", HttpStatus.CONFLICT);
        }
        if (current == TicketStatus.WAITING_APPROVAL && target == TicketStatus.IN_PROGRESS
                && hasPendingSensitiveAction) {
            throw new ApiException("ACTION_PENDING", "仍有未完成的敏感操作申请", HttpStatus.CONFLICT);
        }
        return target;
    }
}
