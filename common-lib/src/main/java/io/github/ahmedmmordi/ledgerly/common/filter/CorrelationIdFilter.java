package io.github.ahmedmmordi.ledgerly.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@NullMarked
@Component
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String MDC_KEY_CORRELATION_ID = "correlationId";
    private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE;

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.trim();
        }

        MDC.put(MDC_KEY_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_CORRELATION_ID, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY_CORRELATION_ID);
        }
    }
}
