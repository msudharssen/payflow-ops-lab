package com.payflow.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Generates a traceId per request, exposes it as the X-Trace-Id response
 * header and in the MDC, and writes one structured access-log line per
 * API request (endpoint, status, durationMs).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    public static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().substring(0, 18).replace("-", "");
        MDC.put(TRACE_ID, traceId);
        response.setHeader("X-Trace-Id", traceId);
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            if (request.getRequestURI().startsWith("/api")) {
                log.info("request completed",
                        kv("endpoint", request.getMethod() + " " + request.getRequestURI()),
                        kv("status", response.getStatus()),
                        kv("durationMs", durationMs));
            }
            MDC.remove(TRACE_ID);
        }
    }
}
