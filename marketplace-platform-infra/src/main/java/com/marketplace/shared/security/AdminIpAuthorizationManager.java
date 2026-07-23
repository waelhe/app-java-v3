package com.marketplace.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.util.matcher.InetAddressMatcher;
import org.springframework.security.util.matcher.InetAddressMatchers;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Authorization manager that checks the client IP against an allowlist
 * for admin endpoints, in addition to the standard role check.
 *
 * <p>This is a defense-in-depth measure: admin endpoints already require
 * {@code hasRole('ADMIN')} via {@code @PreAuthorize}. This manager adds
 * an IP allowlist layer at the HTTP request level.
 *
 * <p>When {@code allowedIpCidrs} is empty, this manager grants access
 * (IP restriction is disabled — the role check in the filter chain is
 * the sole gatekeeper). When non-empty, the client IP must match at
 * least one entry.
 *
 * <p>Uses Spring Security 7.1's {@link InetAddressMatcher} for IP matching.
 *
 * <p>Reference: Spring Security 7.1 Release Highlights:
 * "Added InetAddressMatcher — Introduced InetAddressMatcher in the core
 * module for IP address matching capabilities."
 * https://spring.io/projects/release-highlights
 *
 * <p>Reference: Spring Security 7.1 — Authorization Architecture:
 * "AuthorizationManager s are called by Spring Security's request-based,
 * method-based, and message-based authorization components and are
 * responsible for making final access control decisions."
 * https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html
 */
public class AdminIpAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(AdminIpAuthorizationManager.class);

    private final InetAddressMatcher ipMatcher;
    private final boolean ipRestrictionEnabled;

    /**
     * Creates an admin IP authorization manager from a list of allowed
     * IP addresses/CIDRs.
     *
     * @param allowedIpCidrs the allowed IP addresses/CIDRs; empty list
     *                       disables IP restriction
     */
    public AdminIpAuthorizationManager(List<String> allowedIpCidrs) {
        this.ipRestrictionEnabled = allowedIpCidrs != null && !allowedIpCidrs.isEmpty();
        if (this.ipRestrictionEnabled) {
            this.ipMatcher = InetAddressMatchers.builder()
                    .includeAddresses(allowedIpCidrs)
                    .build();
            log.info("Admin IP restriction enabled — allowed: {}", allowedIpCidrs);
        } else {
            this.ipMatcher = null;
            log.info("Admin IP restriction disabled — no allowed IP CIDRs configured");
        }
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        if (!ipRestrictionEnabled) {
            return new AuthorizationDecision(true);
        }

        HttpServletRequest request = context.getRequest();
        String clientIp = extractClientIp(request);

        try {
            InetAddress inetAddress = InetAddress.getByName(clientIp);
            boolean allowed = ipMatcher.matches(inetAddress);
            if (!allowed) {
                log.warn("Admin access denied from IP: {}", clientIp);
            }
            return new AuthorizationDecision(allowed);
        } catch (java.net.UnknownHostException ex) {
            log.warn("Admin access denied — could not resolve IP: {}", clientIp);
            return new AuthorizationDecision(false);
        }
    }

    /**
     * Extracts the client IP from the request, considering common
     * proxy headers (X-Forwarded-For, X-Real-IP).
     *
     * @param request the HTTP request
     * @return the client IP address
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
