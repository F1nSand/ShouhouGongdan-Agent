package com.aftercare.identity;

public interface LoginRateLimiter {
    boolean isBlocked(String merchantCode, String username);

    void recordFailure(String merchantCode, String username);

    void recordSuccess(String merchantCode, String username);
}
