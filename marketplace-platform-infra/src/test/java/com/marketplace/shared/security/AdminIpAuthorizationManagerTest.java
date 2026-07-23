package com.marketplace.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminIpAuthorizationManager}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>When IP restriction is disabled (empty allowlist), all requests are granted</li>
 *   <li>When IP restriction is enabled, requests from allowed IPs are granted</li>
 *   <li>When IP restriction is enabled, requests from non-allowed IPs are denied</li>
 *   <li>X-Forwarded-For header is respected for proxy scenarios</li>
 * </ul>
 *
 * <p>Reference: Spring Security 7.1 Release Highlights:
 * "Added InetAddressMatcher — Introduced InetAddressMatcher in the core
 * module for IP address matching capabilities."
 * https://spring.io/projects/release-highlights
 */
class AdminIpAuthorizationManagerTest {

    @Test
    void emptyAllowlistGrantsAllRequests() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(List.of());

        AuthorizationResult result = manager.authorize(
                () -> null,
                contextWithRemoteAddr("192.168.1.100"));

        assertThat(result.isGranted()).isTrue();
    }

    @Test
    void nullAllowlistGrantsAllRequests() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(null);

        AuthorizationResult result = manager.authorize(
                () -> null,
                contextWithRemoteAddr("192.168.1.100"));

        assertThat(result.isGranted()).isTrue();
    }

    @Test
    void allowedIpIsGranted() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(
                List.of("192.168.1.0/24"));

        AuthorizationResult result = manager.authorize(
                () -> null,
                contextWithRemoteAddr("192.168.1.50"));

        assertThat(result.isGranted()).isTrue();
    }

    @Test
    void nonAllowedIpIsDenied() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(
                List.of("192.168.1.0/24"));

        AuthorizationResult result = manager.authorize(
                () -> null,
                contextWithRemoteAddr("10.0.0.50"));

        assertThat(result.isGranted()).isFalse();
    }

    @Test
    void loopbackIsGrantedWhenInAllowlist() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(
                List.of("127.0.0.1"));

        AuthorizationResult result = manager.authorize(
                () -> null,
                contextWithRemoteAddr("127.0.0.1"));

        assertThat(result.isGranted()).isTrue();
    }

    @Test
    void xForwardedForHeaderIsRespected() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(
                List.of("10.0.0.1"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.1");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");

        AuthorizationResult result = manager.authorize(
                () -> null,
                new RequestAuthorizationContext(request));

        assertThat(result.isGranted()).isTrue();
    }

    @Test
    void multipleAllowedCidrs() {
        AdminIpAuthorizationManager manager = new AdminIpAuthorizationManager(
                List.of("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"));

        assertThat(manager.authorize(() -> null,
                contextWithRemoteAddr("10.1.2.3")).isGranted()).isTrue();
        assertThat(manager.authorize(() -> null,
                contextWithRemoteAddr("192.168.5.10")).isGranted()).isTrue();
        assertThat(manager.authorize(() -> null,
                contextWithRemoteAddr("172.16.0.1")).isGranted()).isTrue();
        assertThat(manager.authorize(() -> null,
                contextWithRemoteAddr("8.8.8.8")).isGranted()).isFalse();
    }

    private RequestAuthorizationContext contextWithRemoteAddr(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return new RequestAuthorizationContext(request);
    }
}
