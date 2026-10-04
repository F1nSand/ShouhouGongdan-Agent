package com.aftercare.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class AgentBudget {
    public static final int MAX_MODEL_CALLS = 6;
    public static final int MAX_TOOL_CALLS = 8;
    public static final Duration MAX_RUN_TIME = Duration.ofSeconds(60);

    private final Clock clock;
    private final Instant deadline;
    private int modelCalls;
    private int toolCalls;

    public AgentBudget(Clock clock, Duration requestedDuration) {
        this.clock = clock;
        Duration duration = requestedDuration == null || requestedDuration.isNegative()
                || requestedDuration.isZero() || requestedDuration.compareTo(MAX_RUN_TIME) > 0
                ? MAX_RUN_TIME : requestedDuration;
        this.deadline = clock.instant().plus(duration);
    }

    public void consumeModelCall() {
        checkTime();
        if (++modelCalls > MAX_MODEL_CALLS) {
            throw new BudgetExceededException("MODEL_CALL_BUDGET_EXCEEDED");
        }
    }

    public void consumeToolCall() {
        checkTime();
        if (++toolCalls > MAX_TOOL_CALLS) {
            throw new BudgetExceededException("TOOL_CALL_BUDGET_EXCEEDED");
        }
    }

    public void checkTime() {
        if (!clock.instant().isBefore(deadline)) {
            throw new BudgetExceededException("RUN_DEADLINE_EXCEEDED");
        }
    }

    public int modelCalls() {
        return modelCalls;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public Instant deadline() {
        return deadline;
    }

    public static final class BudgetExceededException extends RuntimeException {
        private final String code;

        public BudgetExceededException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
