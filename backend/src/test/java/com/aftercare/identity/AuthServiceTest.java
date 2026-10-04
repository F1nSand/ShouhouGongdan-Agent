package com.aftercare.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.aftercare.identity.AuthRepository.RegisteredAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    @Test
    void loginBindsMerchantAndRole() {
        var tenantA = UUID.fromString("4f8fb896-021e-44ae-9da9-6c8c77db8132");
        var actorA = UUID.fromString("b3bf46f2-7da9-41df-95d0-04a9bd92f986");
        var encoder = new BCryptPasswordEncoder(4);
        var accountA = new RegisteredAccount(tenantA, "MERCHANT_A", actorA, "csr",
                encoder.encode("secret-123"), Role.CUSTOMER_SERVICE, "ACTIVE", "ACTIVE");
        AuthRepository repository = (merchantCode, username) ->
                merchantCode.equals("MERCHANT_A") && username.equals("csr")
                        ? Optional.of(accountA)
                        : Optional.empty();
        var clock = Clock.systemUTC();
        var tokenService = new AccessTokenService(
                "bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==", clock, new ObjectMapper());
        var service = new AuthService(repository, encoder, tokenService);

        var result = service.login("MERCHANT_A", "csr", "secret-123");
        var claims = tokenService.verify(result.accessToken(), "trace-test");

        assertThat(claims.tenantId()).isEqualTo(tenantA);
        assertThat(claims.actorId()).isEqualTo(actorA);
        assertThat(claims.role()).isEqualTo(Role.CUSTOMER_SERVICE);
        assertThat(result.expiresAt()).isAfter(clock.instant());
    }

    @Test
    void loginDoesNotAcceptSameUsernameFromAnotherMerchant() {
        var account = new RegisteredAccount(UUID.randomUUID(), "MERCHANT_A", UUID.randomUUID(), "csr",
                "unused", Role.CUSTOMER_SERVICE, "ACTIVE", "ACTIVE");
        AuthRepository repository = (merchantCode, username) ->
                merchantCode.equals("MERCHANT_A") ? Optional.of(account) : Optional.empty();
        var service = new AuthService(repository, new BCryptPasswordEncoder(4),
                new AccessTokenService("bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==",
                        Clock.systemUTC(), new ObjectMapper()));

        assertThatThrownBy(() -> service.login("MERCHANT_B", "csr", "secret-123"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }
}
