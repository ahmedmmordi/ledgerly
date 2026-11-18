package io.github.ahmedmmordi.ledgerly.gateway.config;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@NullMarked
@Component
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String MDC_KEY_CORRELATION_ID = "correlationId";
    private static final int FILTER_ORDER = -200;

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.trim();
        }

        final String finalCorrelationId = correlationId;
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(builder -> builder.header(HEADER_CORRELATION_ID, finalCorrelationId)).build();

        mutatedExchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, finalCorrelationId);

        MDC.put(MDC_KEY_CORRELATION_ID, finalCorrelationId);
        return chain.filter(mutatedExchange)
                .contextWrite(context -> context.put(MDC_KEY_CORRELATION_ID, finalCorrelationId))
                .doFinally(_ -> MDC.remove(MDC_KEY_CORRELATION_ID));
    }
}
