package com.attributioniq.attribution_iq.api.filter;

import com.attributioniq.attribution_iq.api.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class RequestTracingFilter extends OncePerRequestFilter {
    public static final String MDC_REQUEST_ID_KEY = "requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(ApiPaths.REQUEST_ID_HEADER));
        String previousRequestId = MDC.get(MDC_REQUEST_ID_KEY);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        response.setHeader(ApiPaths.REQUEST_ID_HEADER, requestId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("HTTP request method={} path={} status={} duration_ms={}", request.getMethod(),
                    request.getRequestURI(), response.getStatus(), elapsedMillis);
            if (previousRequestId == null) MDC.remove(MDC_REQUEST_ID_KEY);
            else MDC.put(MDC_REQUEST_ID_KEY, previousRequestId);
        }
    }

    private static String resolveRequestId(String supplied) {
        return supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
    }
}
