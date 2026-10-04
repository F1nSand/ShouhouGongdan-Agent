package com.aftercare.logistics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LogisticsView(UUID orderId, Instant calculatedAt, Instant lastUpdateAt, boolean dataInsufficient,
        List<String> exceptionCodes, List<Event> events) {
    public LogisticsView {
        exceptionCodes = List.copyOf(exceptionCodes);
        events = List.copyOf(events);
    }

    public record Event(String eventCode, Instant eventTime, String carrierCode, String trackingNo,
            String description) {
    }
}
