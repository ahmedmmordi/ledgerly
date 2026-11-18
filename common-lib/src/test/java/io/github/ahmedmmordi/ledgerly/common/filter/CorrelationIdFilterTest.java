package io.github.ahmedmmordi.ledgerly.common.filter;

import static io.github.ahmedmmordi.ledgerly.common.filter.CorrelationIdFilter.HEADER_CORRELATION_ID;
import static io.github.ahmedmmordi.ledgerly.common.filter.CorrelationIdFilter.MDC_KEY_CORRELATION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
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
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@NullMarked
@DisplayName("Correlation ID Servlet Filter Tests")
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
class CorrelationIdFilterTest {

    private static final String SAMPLE_CORRELATION_ID = "c7b94e18-8a22-4e55-9011-37d8251290aa";

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @Test
    @DisplayName("Filter order is HIGHEST_PRECEDENCE")
    void getOrder_returnsHighestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("Propagates incoming X-Correlation-Id header to MDC and response")
    void doFilter_withValidCorrelationId_propagatesToMdcAndResponse() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(HEADER_CORRELATION_ID, SAMPLE_CORRELATION_ID);
        var response = new MockHttpServletResponse();
        var filterChain = new MdcCapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.getCapturedMdc()).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(response.getHeader(HEADER_CORRELATION_ID)).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Trims whitespace in incoming X-Correlation-Id header")
    void doFilter_withWhitespaceInCorrelationId_trimsValue() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(HEADER_CORRELATION_ID, "   " + SAMPLE_CORRELATION_ID + "   ");
        var response = new MockHttpServletResponse();
        var filterChain = new MdcCapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.getCapturedMdc()).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(response.getHeader(HEADER_CORRELATION_ID)).isEqualTo(SAMPLE_CORRELATION_ID);
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Generates new UUID when X-Correlation-Id header is missing")
    void doFilter_withoutHeader_generatesNewUuid() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var filterChain = new MdcCapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        String generatedId = response.getHeader(HEADER_CORRELATION_ID);
        assertThat(generatedId).isNotNull().isNotBlank();
        assertThat(UUID.fromString(generatedId)).isNotNull();
        assertThat(filterChain.getCapturedMdc()).isEqualTo(generatedId);
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @ParameterizedTest(name = "Generates new UUID when X-Correlation-Id is blank: ''{0}''")
    @ValueSource(strings = { "", "   ", "\t", "\n" })
    void doFilter_withBlankHeader_generatesNewUuid(String blankHeader) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(HEADER_CORRELATION_ID, blankHeader);
        var response = new MockHttpServletResponse();
        var filterChain = new MdcCapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        String generatedId = response.getHeader(HEADER_CORRELATION_ID);
        assertThat(generatedId).isNotNull().isNotBlank();
        assertThat(UUID.fromString(generatedId)).isNotNull();
        assertThat(filterChain.getCapturedMdc()).isEqualTo(generatedId);
        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Guarantees MDC cleanup in finally block even when downstream filter throws an exception")
    void doFilter_whenChainThrowsException_guaranteesMdcCleanup() {
        var request = new MockHttpServletRequest();
        request.addHeader(HEADER_CORRELATION_ID, SAMPLE_CORRELATION_ID);
        var response = new MockHttpServletResponse();

        FilterChain faultyChain = (_, _) -> {
            assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isEqualTo(SAMPLE_CORRELATION_ID);
            throw new ServletException("Simulated controller failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, faultyChain)).isInstanceOf(ServletException.class)
                .hasMessage("Simulated controller failure");

        assertThat(MDC.get(MDC_KEY_CORRELATION_ID)).isNull();
    }

    private static final class MdcCapturingFilterChain extends MockFilterChain {

        private final AtomicReference<@Nullable String> capturedMdc = new AtomicReference<>();

        @Override
        public void doFilter(ServletRequest req, ServletResponse res) throws IOException, ServletException {
            capturedMdc.set(MDC.get(MDC_KEY_CORRELATION_ID));
            super.doFilter(req, res);
        }

        @Nullable
        String getCapturedMdc() {
            return capturedMdc.get();
        }
    }
}
