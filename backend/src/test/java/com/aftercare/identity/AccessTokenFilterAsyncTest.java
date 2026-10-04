package com.aftercare.identity;

import java.time.Clock;
import java.util.UUID;

import com.aftercare.identity.AuthRepository.RegisteredAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenFilterAsyncTest {
    private static final String JWT_SECRET = "bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==";

    @Test
    void restoresBearerAuthenticationOnAsyncDispatch() throws Exception {
        var tenantId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var account = new RegisteredAccount(tenantId, "MERCHANT", actorId, "admin", "unused",
                Role.ADMIN, "ACTIVE", "ACTIVE");
        var tokens = new AccessTokenService(JWT_SECRET, Clock.systemUTC(), new ObjectMapper());
        var issued = tokens.issue(account);
        var request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.ASYNC);
        request.addHeader("Authorization", "Bearer " + issued.value());
        var response = new MockHttpServletResponse();
        var filter = new AccessTokenFilter(tokens);

        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                assertThat(authentication).isNotNull();
                assertThat(authentication.getPrincipal()).isInstanceOf(ActorContext.class);
                var actor = (ActorContext) authentication.getPrincipal();
                assertThat(actor.tenantId()).isEqualTo(tenantId);
                assertThat(actor.actorId()).isEqualTo(actorId);
                assertThat(authentication.getAuthorities()).extracting("authority")
                        .containsExactly("ROLE_ADMIN");
            });

            assertThat(response.getStatus()).isEqualTo(200);
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }
}
