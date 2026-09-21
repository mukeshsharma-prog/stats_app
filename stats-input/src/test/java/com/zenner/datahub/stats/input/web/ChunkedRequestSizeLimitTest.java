package com.zenner.datahub.stats.input.web;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.input.config.RequestLimitProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

/**
 * The half of the size limit that {@code Content-Length} cannot cover (T-018).
 *
 * <p>A chunked request declares no length, so the header check passes it and the bound
 * has to be applied while the body is being read. That path has its own failure mode —
 * it can only leave a {@code ServletInputStream} by throwing — and it is invisible to
 * {@code MockMvc}, whose requests always carry a length. Untested, a chunked sender
 * would simply not be limited, and nothing would say so.</p>
 */
class ChunkedRequestSizeLimitTest {

    private static final int LIMIT_BYTES = 64;

    private final RequestSizeLimitFilter filter =
            new RequestSizeLimitFilter(
                    new RequestLimitProperties(DataSize.ofBytes(LIMIT_BYTES), 1_000),
                    new IngestMetrics(new SimpleMeterRegistry()),
                    new ObjectMapper());

    @Test
    @DisplayName("a chunked body over the limit throws while being read, and is not silently truncated")
    void chunkedBodyOverTheLimitIsRejectedMidStream() {
        // Truncating instead of throwing would hand the gate a half-record, which fails
        // envelope validation and is answered 400 "fix your record" — telling Heimdal to
        // correct a record that was never malformed.
        MockHttpServletRequest request = chunkedRequestOf(LIMIT_BYTES * 4);

        assertThatExceptionOfType(PayloadTooLargeException.class)
                .isThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), readingChain()));
    }

    @Test
    @DisplayName("a chunked body inside the limit passes through untouched")
    void chunkedBodyInsideTheLimitIsUnaffected() {
        MockHttpServletRequest request = chunkedRequestOf(LIMIT_BYTES / 2);

        assertThatNoException()
                .isThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), readingChain()));
    }

    /** A chain that actually consumes the body — nothing is enforced until someone reads. */
    private static MockFilterChain readingChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                request.getInputStream().readAllBytes();
                super.doFilter(request, response);
            }
        };
    }

    private static MockHttpServletRequest chunkedRequestOf(int bodyBytes) {
        byte[] body = "x".repeat(bodyBytes).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/internal/stats") {
                    @Override
                    public long getContentLengthLong() {
                        return -1; // chunked: no declared length
                    }

                    @Override
                    public ServletInputStream getInputStream() {
                        return new DelegatingServletInputStream(new ByteArrayInputStream(body));
                    }
                };
        request.setContentType("application/json");
        return request;
    }
}
