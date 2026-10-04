package com.aftercare.approval;

import java.util.UUID;

public record ManualTicketDraftCommand(UUID orderId, String category, String title,
        String description, String priority) {
}
