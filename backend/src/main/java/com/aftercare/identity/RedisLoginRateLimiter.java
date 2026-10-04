package com.aftercare.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisLoginRateLimiter implements LoginRateLimiter {
    private static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final StringRedisTemplate redis;

    public RedisLoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isBlocked(String merchantCode, String username) {
        String count = redis.opsForValue().get(key(merchantCode, username));
        return count != null && Integer.parseInt(count) >= MAX_FAILURES;
    }

    @Override
    public void recordFailure(String merchantCode, String username) {
        String key = key(merchantCode, username);
        Long failures = redis.opsForValue().increment(key);
        if (failures != null && failures == 1L) {
            redis.expire(key, WINDOW);
        }
    }

    @Override
    public void recordSuccess(String merchantCode, String username) {
        redis.delete(key(merchantCode, username));
    }

    private String key(String merchantCode, String username) {
        try {
            byte[] source = (merchantCode.trim().toLowerCase() + ":" + username.trim().toLowerCase())
                    .getBytes(StandardCharsets.UTF_8);
            return "auth:failed:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
