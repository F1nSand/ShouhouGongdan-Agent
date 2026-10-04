package com.aftercare.logistics;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogisticsRulesTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    @Test
    void noUpdateThresholdIsStrictlyGreaterThanFortyEightHours() {
        var exactlyFortyEightHoursAgo = new LogisticsRules.Event("IN_TRANSIT", NOW.minusSeconds(48 * 60 * 60));
        var fortyNineHoursAgo = new LogisticsRules.Event("IN_TRANSIT", NOW.minusSeconds(49 * 60 * 60));

        var atBoundary = LogisticsRules.evaluate("SHIPPED", NOW.minusSeconds(72 * 60 * 60),
                NOW.plusSeconds(24 * 60 * 60), List.of(exactlyFortyEightHoursAgo), NOW);
        var afterBoundary = LogisticsRules.evaluate("SHIPPED", NOW.minusSeconds(72 * 60 * 60),
                NOW.plusSeconds(24 * 60 * 60), List.of(fortyNineHoursAgo), NOW);

        assertThat(atBoundary.reasons()).doesNotContain(LogisticsRules.Reason.NO_UPDATE);
        assertThat(afterBoundary.reasons()).containsExactly(LogisticsRules.Reason.NO_UPDATE);
    }

    @Test
    void overdueAndCarrierExceptionCanBeReportedTogether() {
        var latestException = new LogisticsRules.Event("EXCEPTION", NOW.minusSeconds(60 * 60));

        var assessment = LogisticsRules.evaluate("SHIPPED", NOW.minusSeconds(24 * 60 * 60),
                NOW.minusSeconds(1), List.of(latestException), NOW);

        assertThat(assessment.reasons()).containsExactlyInAnyOrder(
                LogisticsRules.Reason.CARRIER_EXCEPTION, LogisticsRules.Reason.OVERDUE);
    }

    @Test
    void missingShipmentTimeIsReportedInsteadOfUsingPurchaseTime() {
        var assessment = LogisticsRules.evaluate("SHIPPED", null, NOW.plusSeconds(3600), List.of(), NOW);

        assertThat(assessment.dataInsufficient()).isTrue();
        assertThat(assessment.reasons()).isEmpty();
    }
}
