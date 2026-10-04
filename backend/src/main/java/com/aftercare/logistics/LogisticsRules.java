package com.aftercare.logistics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LogisticsRules {
    private static final Duration NO_UPDATE_THRESHOLD = Duration.ofHours(48);

    private LogisticsRules() {
    }

    public static Assessment evaluate(String orderStatus, Instant shippedAt, Instant promisedDeliveryAt,
            List<Event> events, Instant now) {
        var orderedEvents = events.stream().sorted(Comparator.comparing(Event::eventTime)).toList();
        Event latestEvent = orderedEvents.isEmpty() ? null : orderedEvents.getLast();
        var reasons = new ArrayList<Reason>();

        if (latestEvent != null && "EXCEPTION".equals(latestEvent.eventCode())) {
            reasons.add(Reason.CARRIER_EXCEPTION);
        }

        boolean delivered = "DELIVERED".equals(orderStatus)
                || (latestEvent != null && "DELIVERED".equals(latestEvent.eventCode()));
        boolean cancelled = "CANCELLED".equals(orderStatus);
        boolean dataInsufficient = false;
        Instant lastUpdateAt = latestEvent == null ? shippedAt : latestEvent.eventTime();

        if (!delivered && !cancelled && promisedDeliveryAt != null && now.isAfter(promisedDeliveryAt)) {
            reasons.add(Reason.OVERDUE);
        }

        if ("SHIPPED".equals(orderStatus) && !delivered && !cancelled) {
            if (lastUpdateAt == null) {
                dataInsufficient = true;
            }
            else if (Duration.between(lastUpdateAt, now).compareTo(NO_UPDATE_THRESHOLD) > 0) {
                reasons.add(Reason.NO_UPDATE);
            }
        }

        return new Assessment(reasons, dataInsufficient, lastUpdateAt);
    }

    public record Event(String eventCode, Instant eventTime) {
    }

    public record Assessment(List<Reason> reasons, boolean dataInsufficient, Instant lastUpdateAt) {
        public Assessment {
            reasons = List.copyOf(reasons);
        }
    }

    public enum Reason {
        NO_UPDATE,
        CARRIER_EXCEPTION,
        OVERDUE
    }
}
