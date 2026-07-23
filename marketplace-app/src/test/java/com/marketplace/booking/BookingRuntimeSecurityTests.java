package com.marketplace.booking;

import com.marketplace.config.MethodSecurityTestConfig;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

/**
 * Runtime negative security tests for method-level {@code @PreAuthorize} (P2).
 *
 * <p>Verifies that {@code @PreAuthorize} on {@code BookingController} methods
 * correctly rejects users with insufficient roles, returning 403 Forbidden.
 * This is a <b>runtime</b> test (not static analysis) using {@link WebMvcTest}
 * + {@link MethodSecurityTestConfig} which enables method security.
 *
 * <p>This test replaces the static analysis approach from Phase 5 with actual
 * runtime 403 verification, using Spring Security 7.1's method security
 * support in combination with {@code @WithMockUser}.
 *
 * <p>Per Governance Rule 7: "Security features require explicit authorization
 * rules and tests."
 *
 * <p>Reference: Spring Security 7.1 — Testing Method Security:
 * "@WithMockUser — The user with a username of user does not have to exist,
 * since we mock the user object."
 * https://docs.spring.io/spring-security/reference/servlet/test/method.html
 *
 * <p>Reference: Spring Security 7.1 — Method Security:
 * "If the user does not have the required authority, Spring Security returns
 * a 403 Forbidden HTTP status code."
 * https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
 */
@WebMvcTest(controllers = BookingController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
@Import(MethodSecurityTestConfig.class)
class BookingRuntimeSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private BookingMapper bookingMapper;

    @Test
    @WithMockUser(roles = "PROVIDER")
    void providerCannotCreateBookingReturns403() throws Exception {
        UUID listingId = UUID.randomUUID();
        var now = Instant.now();
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/json")
                        .content("""
                                {"listingId": "%s", "startsAt": "%s", "endsAt": "%s"}
                                """.formatted(listingId, now, now.plusSeconds(3600))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCannotCreateBookingReturns403() throws Exception {
        UUID listingId = UUID.randomUUID();
        var now = Instant.now();
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/json")
                        .content("""
                                {"listingId": "%s", "startsAt": "%s", "endsAt": "%s"}
                                """.formatted(listingId, now, now.plusSeconds(3600))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotConfirmBookingReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void consumerCannotConfirmBookingReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotCompleteBookingReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/{id}/complete", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void consumerCanCreateBookingReturnsNot403() throws Exception {
        UUID listingId = UUID.randomUUID();
        var now = Instant.now();
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/json")
                        .content("""
                                {"listingId": "%s", "startsAt": "%s", "endsAt": "%s"}
                                """.formatted(listingId, now, now.plusSeconds(3600))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status)
                            .as("CONSUMER should not get 403 on create booking")
                            .isNotEqualTo(403);
                });
    }
}
