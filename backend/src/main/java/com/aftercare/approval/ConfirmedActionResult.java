package com.aftercare.approval;

import java.util.UUID;

public record ConfirmedActionResult(UUID draftId, UUID ticketId, String ticketNo, String status, UUID actionId) {
    public ConfirmedActionResult(UUID draftId, UUID ticketId, String ticketNo, String status) {
        this(draftId, ticketId, ticketNo, status, null);
    }
}
