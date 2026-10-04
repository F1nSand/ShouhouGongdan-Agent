package com.aftercare.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.aftercare.identity.AuthRepository.RegisteredAccount;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccessTokenService {
    private static final Duration TOKEN_VALIDITY = Duration.ofMinutes(30);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final byte[] secret;
    private final String issuer;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Autowired
    public AccessTokenService(
            @Value("${app.security.jwt-secret}") String base64Secret,
            @Value("${app.security.jwt-issuer:aftersales-agent}") String issuer,
            Clock clock,
            ObjectMapper mapper) {
        this(base64Secret, issuer, clock, mapper, true);
    }

    public AccessTokenService(String base64Secret, Clock clock, ObjectMapper mapper) {
        this(base64Secret, "aftersales-agent", clock, mapper, true);
    }

    private AccessTokenService(String base64Secret, String issuer, Clock clock, ObjectMapper mapper, boolean ignored) {
        try {
            this.secret = Base64.getDecoder().decode(base64Secret);
        }
        catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("JWT_SECRET must be Base64 encoded", invalidBase64);
        }
        if (secret.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must decode to at least 32 bytes");
        }
        this.issuer = issuer;
        this.clock = clock;
        this.mapper = mapper;
    }

    public IssuedToken issue(RegisteredAccount account) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(TOKEN_VALIDITY);
        try {
            String header = URL_ENCODER.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", issuer);
            claims.put("sub", account.actorId().toString());
            claims.put("tenantId", account.tenantId().toString());
            claims.put("role", account.role().name());
            claims.put("iat", issuedAt.getEpochSecond());
            claims.put("exp", expiresAt.getEpochSecond());
            String payload = URL_ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return new IssuedToken(signingInput + "." + sign(signingInput), expiresAt);
        }
        catch (Exception error) {
            throw new IllegalStateException("Could not issue access token", error);
        }
    }

    public ActorContext verify(String token, String traceId) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw new InvalidTokenException();
            }
            Map<String, Object> header = mapper.readValue(Base64.getUrlDecoder().decode(parts[0]), new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new InvalidTokenException();
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expectedSignature = Base64.getUrlDecoder().decode(sign(signingInput));
            if (!MessageDigest.isEqual(actualSignature, expectedSignature)) {
                throw new InvalidTokenException();
            }
            Map<String, Object> claims = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), new TypeReference<>() {});
            long now = clock.instant().getEpochSecond();
            long issuedAt = numberClaim(claims, "iat");
            long expiresAt = numberClaim(claims, "exp");
            if (!issuer.equals(claims.get("iss")) || issuedAt > now + 60 || expiresAt <= now
                    || expiresAt - issuedAt > TOKEN_VALIDITY.toSeconds()) {
                throw new InvalidTokenException();
            }
            return new ActorContext(
                    UUID.fromString(stringClaim(claims, "tenantId")),
                    UUID.fromString(stringClaim(claims, "sub")),
                    Role.valueOf(stringClaim(claims, "role")),
                    traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        }
        catch (InvalidTokenException invalid) {
            throw invalid;
        }
        catch (Exception invalid) {
            throw new InvalidTokenException();
        }
    }

    private String sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private long numberClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new InvalidTokenException();
    }

    private String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw new InvalidTokenException();
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() {
            super("Invalid or expired access token");
        }
    }
}
