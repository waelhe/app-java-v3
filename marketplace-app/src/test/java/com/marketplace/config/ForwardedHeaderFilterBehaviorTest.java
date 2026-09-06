package com.marketplace.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire-level mechanism that
 * {@code server.forward-headers-strategy=FRAMEWORK} activates in production
 * (profile gate: {@code ForwardHeadersProdConfigTest}).
 *
 * <p>Spring Boot 4.1.1 {@code ServletWebServerConfiguration#forwardedHeaderFilter}
 * registers exactly this filter class for REQUEST/ASYNC/ERROR dispatches at
 * {@code Ordered.HIGHEST_PRECEDENCE} — ahead of every security filter chain —
 * when the property equals {@code framework} (cached source:
 * {@code scripts/prod-design-docs/src-verify/}). Verified against the
 * spring-web 7.0.9 source of {@link ForwardedHeaderFilter}:</p>
 * <ul>
 * <li>{@code ForwardedHeaderExtractingRequest.getScheme()/getServerName()/
 * getServerPort()} come from the forwarded headers; a missing port defaults
 * to 443 (secure) / 80 (insecure) — so the fix holds even for proxies that
 * do not send {@code X-Forwarded-Port}.</li>
 * <li>{@code ForwardedHeaderExtractingRequest extends
 * ForwardedHeaderRemovingRequest}: forwarded headers are consumed and hidden
 * from the application afterwards.</li>
 * </ul>
 *
 * <p>Unit level (no Spring context), mirroring
 * {@code JwkSourceProdHardeningTest}.</p>
 */
class ForwardedHeaderFilterBehaviorTest {

    private final ForwardedHeaderFilter filter = new ForwardedHeaderFilter();

    @Test
    void honorsForwardedProtoHostAndPortFromTrustedProxy() throws Exception {
        MockHttpServletRequest request = requestFromProxy();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "marketplace.example.org");
        request.addHeader("X-Forwarded-Port", "443");

        HttpServletRequest downstream = filterAndCapture(request);

        assertThat(downstream.getScheme()).isEqualTo("https");
        assertThat(downstream.isSecure()).isTrue();
        assertThat(downstream.getServerName()).isEqualTo("marketplace.example.org");
        assertThat(downstream.getServerPort()).isEqualTo(443);
        // consumed, not re-exposed to the application
        assertThat(downstream.getHeader("X-Forwarded-Proto")).isNull();
        assertThat(downstream.getHeader("X-Forwarded-Host")).isNull();
        assertThat(downstream.getHeader("X-Forwarded-Port")).isNull();
    }

    @Test
    void defaultsPortToSchemeWhenProxyOmitsForwardedPort() throws Exception {
        // Proxies are not required to send X-Forwarded-Port; the filter must
        // still correct the port (443 for https) so request-derived absolute
        // URLs do not carry the internal container port.
        MockHttpServletRequest request = requestFromProxy();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "marketplace.example.org");

        HttpServletRequest downstream = filterAndCapture(request);

        assertThat(downstream.getScheme()).isEqualTo("https");
        assertThat(downstream.getServerPort()).isEqualTo(443);
    }

    @Test
    void leavesRequestUntouchedWithoutForwardedHeaders() throws Exception {
        MockHttpServletRequest request = requestFromProxy();

        HttpServletRequest downstream = filterAndCapture(request);

        assertThat(downstream.getScheme()).isEqualTo("http");
        assertThat(downstream.getServerName()).isEqualTo("10.10.10.10");
        assertThat(downstream.getServerPort()).isEqualTo(8080);
    }

    private static MockHttpServletRequest requestFromProxy() {
        // The container-side view before forwarded-header processing: the
        // proxy terminates TLS and rewrites Host, so the raw request is plain
        // HTTP on the internal port.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setScheme("http");
        request.setServerName("10.10.10.10");
        request.setServerPort(8080);
        return request;
    }

    private HttpServletRequest filterAndCapture(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return (HttpServletRequest) chain.getRequest();
    }
}
