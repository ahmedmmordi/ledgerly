package io.github.ahmedmmordi.ledgerly.gateway.config;

import static io.github.ahmedmmordi.ledgerly.gateway.config.CorrelationIdWebFilter.HEADER_CORRELATION_ID;
import static io.github.ahmedmmordi.ledgerly.gateway.config.CorrelationIdWebFilter.MDC_KEY_CORRELATION_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NullMarked
@DisplayName("Correlation ID WebFilter Tests")
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
class CorrelationIdWebFilterTest {

    private static final String SAMPLE_CORRELATION_ID = "c7b94e18-8a22-4e55-9011-37d8251290aa";

    private CorrelationIdWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdWebFilter();
        MDC.clear();
    }

    @Test
    @DisplayName("Filter order is -200 (executes before JWT auth filter at -100)")
    void getOrder_returnsMinus200() {
        assertThat(filter.getOrder()).isEqualTo(-200);
    }

    @Test
    @DisplayName("Propagates incoming X-Correlation-Id to mutated request and response headers")
    void filter_withValidCorrelationId_propagatesToDownstreamAndResponse() {
        var request = MockServerHttpRequest.get("/api/v1/accounts").header(HEADER_CORRELATION_ID, SAMPLE_CORRELATION_ID)
                .build();
        var exchange = MockServerWebExchange.from(request);
        var chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ServerHttpRequest downstreamRequest = chain.getCapturedRequest();
        assertThat(downstreamRequest).isNotNull();
        assertThat(downstreamRequest.getHeaders().getFirst(HEADER_CORRELATION_ID)).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER_CORRELATION_ID))
                .isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(chain.getCapturedMdc()).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(chain.getCapturedReactorContext()).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Trims whitespace in incoming X-Correlation-Id header")
    void filter_withWhitespaceInCorrelationId_trimsValue() {
        var request = MockServerHttpRequest.get("/api/v1/accounts")
                .header(HEADER_CORRELATION_ID, "   " + SAMPLE_CORRELATION_ID + "   ").build();
        var exchange = MockServerWebExchange.from(request);
        var chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ServerHttpRequest downstreamRequest = chain.getCapturedRequest();
        assertThat(downstreamRequest).isNotNull();
        assertThat(downstreamRequest.getHeaders().getFirst(HEADER_CORRELATION_ID)).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER_CORRELATION_ID))
                .isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Generates new UUID when X-Correlation-Id header is missing")
    void filter_withoutHeader_generatesNewUuid() {
        var request = MockServerHttpRequest.get("/api/v1/accounts").build();
        var exchange = MockServerWebExchange.from(request);
        var chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ServerHttpRequest downstreamRequest = chain.getCapturedRequest();
        assertThat(downstreamRequest).isNotNull();
        String downstreamId = downstreamRequest.getHeaders().getFirst(HEADER_CORRELATION_ID);
        String responseId = exchange.getResponse().getHeaders().getFirst(HEADER_CORRELATION_ID);

        assertThat(downstreamId).isNotNull().isNotBlank();
        assertThat(responseId).isEqualTo(downstreamId);
        assertThat(UUID.fromString(downstreamId)).isNotNull();
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @ParameterizedTest(name = "Generates new UUID when X-Correlation-Id is blank: ''{0}''")
    @ValueSource(strings = { "", "   ", "\t", "\n" })
    void filter_withBlankHeader_generatesNewUuid(String blankHeader) {
        var request = MockServerHttpRequest.get("/api/v1/accounts").header(HEADER_CORRELATION_ID, blankHeader).build();
        var exchange = MockServerWebExchange.from(request);
        var chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ServerHttpRequest downstreamRequest = chain.getCapturedRequest();
        assertThat(downstreamRequest).isNotNull();
        String downstreamId = downstreamRequest.getHeaders().getFirst(HEADER_CORRELATION_ID);
        String responseId = exchange.getResponse().getHeaders().getFirst(HEADER_CORRELATION_ID);

        assertThat(downstreamId).isNotNull().isNotBlank();
        assertThat(responseId).isEqualTo(downstreamId);
        assertThat(UUID.fromString(downstreamId)).isNotNull();
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Guarantees MDC cleanup on reactive Mono error")
    void filter_whenChainErrors_cleansUpMdc() {
        var request = MockServerHttpRequest.get("/api/v1/accounts").header(HEADER_CORRELATION_ID, SAMPLE_CORRELATION_ID)
                .build();
        var exchange = MockServerWebExchange.from(request);

        WebFilterChain faultyChain = _ -> Mono.error(new IllegalStateException("Downstream filter failure"));

        StepVerifier.create(filter.filter(exchange, faultyChain)).expectError(IllegalStateException.class).verify();

        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    private static final class CapturingWebFilterChain implements WebFilterChain {

        private final AtomicReference<@Nullable ServerHttpRequest> capturedRequest = new AtomicReference<>();
        private final AtomicReference<@Nullable String> capturedMdc = new AtomicReference<>();
        private final AtomicReference<@Nullable String> capturedReactorContext = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            capturedRequest.set(exchange.getRequest());
            capturedMdc.set(MDC.get(MDC_KEY_CORRELATION_ID));
            return Mono.deferContextual(ctx -> {
                capturedReactorContext.set(ctx.getOrDefault(MDC_KEY_CORRELATION_ID, null));
                return Mono.empty();
            });
        }

        @Nullable
        ServerHttpRequest getCapturedRequest() {
            return capturedRequest.get();
        }

        @Nullable
        String getCapturedMdc() {
            return capturedMdc.get();
        }

        @Nullable
        String getCapturedReactorContext() {
            return capturedReactorContext.get();
        }
    }
}
