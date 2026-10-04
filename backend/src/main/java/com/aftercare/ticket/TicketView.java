package com.aftercare.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record TicketView(UUID id, String ticketNo, UUID draftId, UUID sourceRunId, UUID orderId,
        String category, String title, String description, String priority, TicketStatus status,
        JsonNode evidenceSnapshot, UUID createdBy, UUID assignedTo, String resolution, long version,
        Instant createdAt, Instant updatedAt, List<TicketEventView> events) {
    public TicketView {
        evidenceSnapshot = evidenceSnapshot == null ? null : evidenceSnapshot.deepCopy();
        events = events == null ? List.of() : List.copyOf(events);
    }

    @Override
    public JsonNode evidenceSnapshot() {
        return evidenceSnapshot == null ? null : evidenceSnapshot.deepCopy();
    }
}
