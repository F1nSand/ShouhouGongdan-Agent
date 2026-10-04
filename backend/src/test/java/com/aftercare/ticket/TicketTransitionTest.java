package com.aftercare.ticket;

import com.aftercare.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTransitionTest {

    @Test
    void resolutionRequiresSummary() {
        assertThatThrownBy(() -> TicketStateMachine.transition(
                TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, "  ", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("RESOLUTION_REQUIRED");
    }

    @Test
    void invalidPathDoesNotChangeStatus() {
        assertThatThrownBy(() -> TicketStateMachine.transition(
                TicketStatus.OPEN, TicketStatus.RESOLVED, "已经处理", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void unresolvedSensitiveActionBlocksResolution() {
        assertThatThrownBy(() -> TicketStateMachine.transition(
                TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, "已处理", true))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("ACTION_PENDING");
    }

    @Test
    void allowedTransitionsReturnTargetStatus() {
        assertThat(TicketStateMachine.transition(
                TicketStatus.OPEN, TicketStatus.ASSIGNED, null, false)).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(TicketStateMachine.transition(
                TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, null, false)).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(TicketStateMachine.transition(
                TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS, null, false)).isEqualTo(TicketStatus.IN_PROGRESS);
    }
}
