package com.marketplace.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-2 of the Spring Security 7.1.1 compliance wave — the "session until
 * logout" mechanism, pinned at the documented component level (the full
 * automatic Boot wiring — cookie serializer arming, Redis TTL, the live
 * Set-Cookie — is pinned end-to-end by
 * {@code RememberMeSessionUntilLogoutIntegrationTest} in marketplace-app).
 *
 * <p>Every assertion below is the official documented behavior, cited from
 * the Spring Session 4.1.1 reference (Spring Security Integration) and
 * verified against the spring-session-core 4.1.1 sources:</p>
 * <ul>
 *   <li>"The support: Changes the session expiration length. Ensures that
 *       the session cookie expires at Integer.MAX_VALUE."</li>
 *   <li>{@code SpringSessionRememberMeServices.loginSuccess} raises the
 *       session to {@code setMaxInactiveInterval(2592000)} (the class's
 *       {@code THIRTY_DAYS_SECONDS} default — the official default IS the
 *       policy, no custom validity) and marks the request with
 *       {@code REMEMBER_ME_LOGIN_ATTR}.</li>
 *   <li>{@code DefaultCookieSerializer#getCookieMaxAge}: "If specified, the
 *       cookie will be written as Integer.MAX_VALUE" when the request
 *       attribute is present — the armed-serializer leg.</li>
 *   <li>The reference's "optionally customize" knob
 *       ({@code setAlwaysRemember(true)}) — the production bean's choice,
 *       because the default login page posts no remember-me parameter.</li>
 * </ul>
 */
class RememberMeSessionUntilLogoutTest {

    private SpringSessionRememberMeServices services;

    @BeforeEach
    void setUp() {
        // The production bean, exactly as SecurityConfig#rememberMeServices
        // defines it (the reference's documented listing).
        services = new SpringSessionRememberMeServices();
        services.setAlwaysRemember(true);
    }

    @Test
    void loginSuccessRaisesTheSessionToTheDocumentedThirtyDays() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        services.loginSuccess(request, new MockHttpServletResponse(),
                new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        // THIRTY_DAYS_SECONDS = 2592000 — the official default validity.
        assertThat(session.getMaxInactiveInterval()).isEqualTo(2592000);
    }

    @Test
    void loginSuccessMarksTheRequestWithTheRememberMeLoginAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        services.loginSuccess(request, new MockHttpServletResponse(),
                new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        // The attribute the armed CookieSerializer looks for — the
        // Integer.MAX_VALUE leg's trigger.
        assertThat(request.getAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR)).isNotNull();
    }

    @Test
    void alwaysRememberNeedsNoParameterTheDefaultLoginPageNeverSends() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        // No remember-me request parameter anywhere (the framework's default
        // login page has no checkbox): the alwaysRemember knob carries the
        // every-login policy, per the reference's "optionally customize".
        services.loginSuccess(request, new MockHttpServletResponse(),
                new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        assertThat(sessionIntervalOf(request)).isEqualTo(2592000);
        assertThat(request.getAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR)).isNotNull();
    }

    @Test
    void armedSerializerWritesTheSessionCookieAtIntegerMaxValue() {
        // The serializer armed exactly the way SpringHttpSessionConfiguration
        // arms the default one when the SpringSessionRememberMeServices bean
        // is present (source-verified: setRememberMeRequestAttribute(
        // REMEMBER_ME_LOGIN_ATTR)).
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setRememberMeRequestAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setSession(new MockHttpSession());
        services.loginSuccess(request, response,
                new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        CookieSerializer.CookieValue cookieValue =
                new CookieSerializer.CookieValue(request, response, "session-id-under-test");
        serializer.writeCookieValue(cookieValue);

        // "Ensures that the session cookie expires at Integer.MAX_VALUE."
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=2147483647");
    }

    @Test
    void unmarkedLoginLeavesTheSerializerAtTheDefaultCookieMaxAge() {
        // The negative control for the attribute trigger: without the
        // remember-me mark, getCookieMaxAge takes the default (null →
        // session-scoped) leg, never Integer.MAX_VALUE.
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setRememberMeRequestAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        CookieSerializer.CookieValue cookieValue =
                new CookieSerializer.CookieValue(request, response, "session-id-under-test");
        serializer.writeCookieValue(cookieValue);

        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Max-Age=2147483647");
    }

    @Test
    void logoutRemovesTheSecurityContextFromTheSession() {
        // The class implements LogoutHandler and RememberMeConfigurer wires
        // it into the logout handlers automatically — its documented logout
        // removes the stored security context (the session itself is
        // invalidated by the default SecurityContextLogoutHandler).
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        session.setAttribute("SPRING_SECURITY_CONTEXT", new Object());

        services.logout(request, new MockHttpServletResponse(),
                new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    private static int sessionIntervalOf(MockHttpServletRequest request) {
        return ((MockHttpSession) request.getSession()).getMaxInactiveInterval();
    }
}
