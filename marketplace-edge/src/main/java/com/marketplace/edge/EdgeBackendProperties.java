package com.marketplace.edge;

import java.net.URI;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The backend connection contract, enforced at BINDING time — Boot's own
 * fail-fast mechanism replacing the removed manual ApplicationRunner guard
 * (the CWE-319 leg). Official basis:
 *
 * <ul>
 * <li>Boot reference, "Externalized Configuration / Third-party
 * Configuration": {@code @ConfigurationProperties} + {@code @Validated}
 * classes "are validated ... any validation errors ... cause the
 * application to fail to start" — the boot-time failure mode the manual
 * runner used to provide, carried by the framework instead.</li>
 * <li>Spring Cloud Gateway Server Web MVC reference, Token Relay: the
 * filter "sets the access token of the currently authenticated user on
 * the downstream request" — the connection this record guards carries
 * the user's access token, so plaintext transport off-loopback is a
 * measured CWE-319, not a style preference.</li>
 * </ul>
 *
 * <p>The accepted transports: {@code https} (the default posture),
 * loopback (local development — the traffic never leaves the machine),
 * or an explicit, single-property opt-in
 * ({@code edge.backend.allow-insecure-transport=true}, env
 * {@code EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT} — the same variable
 * {@code .railway/railway.ts} already preserves) for a measured internal
 * plaintext backend. Every other {@code http://} URL fails the boot.</p>
 *
 * <p>Binding names follow relaxed binding, so the Railway variable
 * {@code EDGE_BACKEND_URL} binds {@code edge.backend.url} exactly as the
 * previous raw placeholder resolved it — no deployment changes.</p>
 *
 * @param url the backend API base URL the TokenRelay route targets
 * @param allowInsecureTransport the explicit plaintext opt-in
 */
@ConfigurationProperties("edge.backend")
@Validated
record EdgeBackendProperties(
        @DefaultValue("http://localhost:8080") URI url,
        @DefaultValue("false") boolean allowInsecureTransport) {

    /**
     * The transport gate: https, loopback, or the explicit opt-in — never
     * silent plaintext off-loopback.
     *
     * @return true when the token-carrying connection's transport is acceptable
     */
    @AssertTrue(message = "edge.backend.url must use https — the TokenRelay filter puts the user's "
            + "access token on this connection (CWE-319). Loopback http is accepted for local "
            + "development; any other plaintext backend requires the explicit, measured opt-in "
            + "edge.backend.allow-insecure-transport=true.")
    boolean isTransportAcceptable() {
        if (url == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(url.getScheme())) {
            return true;
        }
        String host = url.getHost();
        boolean loopback = host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host));
        return loopback || allowInsecureTransport;
    }
}
