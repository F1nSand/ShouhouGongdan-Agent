package com.aftercare.ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketEventView(UUID id, UUID actorId, String eventType, TicketStatus fromStatus,
        TicketStatus toStatus, String comment, Instant createdAt) {
}
