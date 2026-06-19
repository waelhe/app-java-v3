package com.marketplace.shared.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Resolves the bearer token from either the standard {@code Authorization} header
 * or the {@code session_token} cookie.
 *
 * <p><b>Why both?</b> The protected API chain uses Spring Security's OAuth2 resource
 * server, which by default only reads the {@code Authorization: Bearer} header. After
 * OAuth2 social login, the JWT is set as an HttpOnly cookie (so browser JS cannot
 * extract it to set the Bearer header). Without this resolver, every {@code /api/**}
 * call after social login returns 401.
 *
 * <p>This resolver enables both patterns:
 * <ul>
 *   <li><b>API clients</b> (mobile apps, SPAs with token in memory): send
 *       {@code Authorization: Bearer <jwt>} — standard OAuth2 pattern</li>
 *   <li><b>Browser after social login</b>: browser sends the {@code session_token}
 *       cookie automatically — no JS access needed (HttpOnly protects against XSS)</li>
 * </ul>
 *
 * <p><b>Security</b>: the cookie is {@code HttpOnly + Secure + SameSite=Strict} (set by
 * {@link OAuth2LoginSuccessHandler}), so it is not vulnerable to XSS theft or CSRF
 * submission. The Bearer header is the standard OAuth2 token carrier.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html">Spring Security — Bearer Token Resolution</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6750">RFC 6750 — Bearer Token Usage</a>
 */
@Component
public class CookieAndHeaderBearerTokenResolver implements BearerTokenResolver {

    /** Cookie name set by {@link OAuth2LoginSuccessHandler}. */
    static final String SESSION_COOKIE_NAME = "session_token";

    @Override
    public String resolve(HttpServletRequest request) {
        // 1. Try the standard Authorization: Bearer header first.
        String headerToken = resolveFromAuthorizationHeader(request);
        if (headerToken != null) {
            return headerToken;
        }

        // 2. Fall back to the session_token cookie (browser after social login).
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    private String resolveFromAuthorizationHeader(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
