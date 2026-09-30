package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.core.correlation.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationContext.CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = request.getHeader("X-Request-Id");
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = request.getHeader("correlation-id");
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        CorrelationContext.setCorrelationId(correlationId);
        request.setAttribute(CorrelationContext.CORRELATION_ID_KEY, correlationId);
        response.setHeader(CorrelationContext.CORRELATION_ID_HEADER, correlationId);

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response), true);

        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
