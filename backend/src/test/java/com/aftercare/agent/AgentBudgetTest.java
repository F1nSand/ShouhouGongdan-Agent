package com.aftercare.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBudgetTest {

    @Test
    void callsAndDeadlineAreBounded() {
        var clock = new MutableClock(Instant.parse("2026-09-27T00:00:00Z"));
        var budget = new AgentBudget(clock, Duration.ofSeconds(60));

        for (int call = 0; call < 6; call++) {
            budget.consumeModelCall();
        }
        assertThatThrownBy(budget::consumeModelCall)
                .isInstanceOf(AgentBudget.BudgetExceededException.class)
                .extracting(error -> ((AgentBudget.BudgetExceededException) error).code())
                .isEqualTo("MODEL_CALL_BUDGET_EXCEEDED");

        for (int call = 0; call < 8; call++) {
            budget.consumeToolCall();
        }
        assertThatThrownBy(budget::consumeToolCall)
                .isInstanceOf(AgentBudget.BudgetExceededException.class)
                .extracting(error -> ((AgentBudget.BudgetExceededException) error).code())
                .isEqualTo("TOOL_CALL_BUDGET_EXCEEDED");

        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(budget::checkTime)
                .isInstanceOf(AgentBudget.BudgetExceededException.class)
                .extracting(error -> ((AgentBudget.BudgetExceededException) error).code())
                .isEqualTo("RUN_DEADLINE_EXCEEDED");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
