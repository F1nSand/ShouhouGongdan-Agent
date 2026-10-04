package com.aftercare.common;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Trace-Id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestedTraceId = request.getHeader(HEADER_NAME);
        String traceId = requestedTraceId != null && SAFE_TRACE_ID.matcher(requestedTraceId).matches()
                ? requestedTraceId
                : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        response.setHeader(HEADER_NAME, traceId);
        try {
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove("traceId");
        }
    }
}
