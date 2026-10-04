package com.aftercare.identity;

import java.util.Optional;
import java.util.UUID;

public interface AuthRepository {
    Optional<RegisteredAccount> findAccount(String merchantCode, String username);

    record RegisteredAccount(
            UUID tenantId,
            String merchantCode,
            UUID actorId,
            String username,
            String passwordHash,
            Role role,
            String userStatus,
            String tenantStatus) {
    }
}
