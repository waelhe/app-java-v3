package com.marketplace.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-8 of the Spring Security 7.1.1 compliance wave — the documented logout
 * cleanup component, pinned at the exact shape the reference prescribes
 * (Handling Logouts, "Using Clear-Site-Data to Clear Cookies"):
 *
 * <pre>{@code
 * HeaderWriterLogoutHandler clearSiteData = new HeaderWriterLogoutHandler(
 *         new ClearSiteDataHeaderWriter(Directive.COOKIES));
 * http.logout((logout) -> logout.addLogoutHandler(clearSiteData));
 * }</pre>
 *
 * <p>The component's own documented semantics: the {@code Clear-Site-Data}
 * header instructs browsers to clear the site's cookies on logout ("a handy
 * and secure way to ensure that everything, including the session cookie, is
 * cleaned up on logout"), and the writer engages on secure requests only
 * (its {@code SecureRequestMatcher}) — so plain-http local development is
 * unaffected while production https gets the cleanup. The chain-level wiring
 * (the addLogoutHandler call on the form-login chain) boots end-to-end in
 * {@code RememberMeSessionUntilLogoutIntegrationTest} (marketplace-app).</p>
 */
class ClearSiteDataLogoutHandlerTest {

    private HeaderWriterLogoutHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HeaderWriterLogoutHandler(new ClearSiteDataHeaderWriter(ClearSiteDataHeaderWriter.Directive.COOKIES));
    }

    @Test
    void writesTheCookiesDirectiveOnSecureRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.logout(request, response, new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        assertThat(response.getHeader("Clear-Site-Data")).isEqualTo("\"cookies\"");
    }

    @Test
    void staysSilentOnPlainHttpRequests() {
        // The documented SecureRequestMatcher behavior: local http
        // development never sees the header (no exception, no partial write).
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.logout(request, response, new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER"));

        assertThat(response.getHeader("Clear-Site-Data")).isNull();
    }
}
