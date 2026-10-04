package com.aftercare.identity;

import java.time.Instant;

import com.aftercare.identity.AuthRepository.RegisteredAccount;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService tokenService;
    private final LoginRateLimiter rateLimiter;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, AccessTokenService tokenService) {
        this(repository, passwordEncoder, tokenService, new LoginRateLimiter() {
            @Override
            public boolean isBlocked(String merchantCode, String username) {
                return false;
            }

            @Override
            public void recordFailure(String merchantCode, String username) {
            }

            @Override
            public void recordSuccess(String merchantCode, String username) {
            }
        });
    }

    @Autowired
    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, AccessTokenService tokenService,
            LoginRateLimiter rateLimiter) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    public LoginResult login(String merchantCode, String username, String password) {
        if (rateLimiter.isBlocked(merchantCode, username)) {
            throw new RateLimitedException();
        }
        RegisteredAccount account = repository.findAccount(merchantCode, username)
                .filter(candidate -> "ACTIVE".equals(candidate.userStatus())
                        && "ACTIVE".equals(candidate.tenantStatus()))
                .filter(candidate -> passwordEncoder.matches(password, candidate.passwordHash()))
                .orElse(null);
        if (account == null) {
            rateLimiter.recordFailure(merchantCode, username);
            throw new InvalidCredentialsException();
        }
        rateLimiter.recordSuccess(merchantCode, username);
        var issued = tokenService.issue(account);
        String traceId = MDC.get("traceId");
        return new LoginResult(issued.value(), issued.expiresAt(),
                new ActorContext(account.tenantId(), account.actorId(), account.role(), traceId));
    }

    public record LoginResult(String accessToken, Instant expiresAt, ActorContext actor) {
    }

    public static final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("商家编码、用户名或密码不正确，或账号已停用");
        }
    }

    public static final class RateLimitedException extends RuntimeException {
        public RateLimitedException() {
            super("登录尝试过于频繁，请稍后再试");
        }
    }
}
