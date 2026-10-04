package com.aftercare.approval;

import java.util.UUID;

public record DecisionResult(UUID actionId, String status, long version) {
}
