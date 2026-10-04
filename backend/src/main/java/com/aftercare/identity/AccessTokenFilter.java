package com.aftercare.identity;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccessTokenFilter extends OncePerRequestFilter {
    private final AccessTokenService tokenService;

    public AccessTokenFilter(AccessTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                ActorContext actor = tokenService.verify(authorization.substring(7), MDC.get("traceId"));
                var authority = new SimpleGrantedAuthority("ROLE_" + actor.role().name());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(actor, null, List.of(authority)));
            }
            catch (AccessTokenService.InvalidTokenException invalid) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"登录状态无效或已过期\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
