package com.marketplace.booking;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

/**
 * A-03 (official-compliance plan 0.6 + 0.9 — the unified error-contract
 * tests, the unit's measured gate): every REST error surface of the booking
 * journey answers the RFC 9457 {@code application/problem+json} contract,
 * pinning BOTH sides of the ordered composition the official Framework
 * reference prescribes.
 *
 * <p><b>The three layers of the ordered composition
 * (mvc-ann-rest-exceptions.html, measured):</b>
 * <ol>
 *   <li><b>The house specific advice, ordered ahead</b> — the official doc:
 *       "you'll need to ensure your handler is ordered ahead of the one
 *       configured by Spring Boot whose order is 0." This layer owns the
 *       surfaces whose documented house contract is richer than the
 *       automatic body: the 404 taxonomy (type/errorCode/category/instance)
 *       through the official {@code ErrorResponseException} carrier, the
 *       403 ownership gate, and the 400 validation body with the
 *       {@code fieldErrors} extension — the contract that was dead code at
 *       the HTTP layer before the ordering (the automatic order-0 advice
 *       shadowed it).</li>
 *   <li><b>Boot's automatic handler (order 0)</b> — {@code
 *       spring.mvc.problemdetails.enabled=true} autoconfigures the {@code
 *       ProblemDetailsExceptionHandler} that renders every other built-in
 *       Spring MVC exception with the Framework's own RFC 9457 body. The
 *       405 test proves this layer is live: no house handler exists for
 *       {@code HttpRequestMethodNotSupportedException}, so the automatic
 *       body is the one on the wire.</li>
 *   <li><b>The house fallback advice, ordered last</b> — the
 *       uncaught-exception 500 safety net ({@code GlobalErrorFallbackHandler},
 *       LOWEST_PRECEDENCE): measured necessity, a catch-all inside the
 *       ahead-ordered advice swallowed the built-ins (405 answered 500)
 *       before the split.</li>
 * </ol>
 *
 * <p>The service layer is mocked to throw exactly what the real service
 * throws (measured): {@code ResourceNotFoundException} for the missing /
 * outsider booking (the published "anyone else gets 404" privacy contract),
 * {@code AccessDeniedException} for the ownership gate on writes.
 */
@WebMvcTest(controllers = BookingController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class BookingErrorContractWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private BookingMapper bookingMapper;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    /**
     * The 404 contract on the house side: the missing booking answers the
     * taxonomy problem body — type, title, status, detail, instance,
     * errorCode, category — riding the official ErrorResponseException
     * carrier through the ordered house advice.
     */
    @Test
    @WithMockUser
    void getById_missingBooking_answers404ProblemDetail_a03() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookingService.getByIdForUser(any(), any()))
                .thenThrow(new ResourceNotFoundException("Booking", id));

        mockMvc.perform(get("/api/v1/bookings/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/not-found"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Booking not found: " + id))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings/" + id))
                .andExpect(jsonPath("$.errorCode").value("NF-001"))
                .andExpect(jsonPath("$.category").value("not-found"));
    }

    /**
     * The published privacy contract, rendered: the endpoint's OpenAPI
     * document promises "anyone else gets 404" — the authenticated
     * non-participant probing a booking that exists gets the SAME 404
     * problem body as a missing booking (no existence leak). The service is
     * mocked to throw exactly what the measured service code throws for the
     * outsider (the service-level pin lives in BookingServiceTest).
     */
    @Test
    @WithMockUser
    void getById_authenticatedOutsider_answers404ProblemDetail_a03() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookingService.getByIdForUser(any(), any()))
                .thenThrow(new ResourceNotFoundException("Booking", id));

        mockMvc.perform(get("/api/v1/bookings/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/not-found"))
                .andExpect(jsonPath("$.errorCode").value("NF-001"))
                .andExpect(jsonPath("$.detail").value("Booking not found: " + id));
    }

    /**
     * The 403 ownership-gate contract: an AccessDeniedException raised inside
     * the handler (the service-layer ownership check, exactly what the real
     * service throws) renders the AUTHZ taxonomy problem body through the
     * ordered house advice.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void confirm_ownershipDenied_answers403ProblemDetail_a03() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookingService.confirm(any(), any()))
                .thenThrow(new AccessDeniedException("You are not the provider for this booking"));

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", id))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/access-denied"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings/" + id + "/confirm"))
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-001"))
                .andExpect(jsonPath("$.category").value("authz"));
    }

    /**
     * THE ordering pin (compliance 0.9's measured defect): the 400 validation
     * answer carries the house {@code fieldErrors} extension — which only
     * holds if the house advice is ordered AHEAD of Boot's automatic
     * order-0 {@code ProblemDetailsExceptionHandler}. Before the ordering,
     * the automatic handler took {@code MethodArgumentNotValidException}
     * first and this body never reached the wire (the documented contract
     * was dead code at the HTTP layer).
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_invalidBody_answers400ProblemDetailWithFieldErrors_a03() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/validation"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings"))
                .andExpect(jsonPath("$.errorCode").value("VAL-001"))
                .andExpect(jsonPath("$.category").value("validation"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").exists())
                .andExpect(jsonPath("$.fieldErrors[0].message").exists());
    }

    /**
     * The official automatic side, proven live: {@code POST} on the GET-only
     * {@code /api/v1/bookings/{id}} path raises the built-in
     * {@code HttpRequestMethodNotSupportedException}, for which the house
     * advice has NO handler — Boot's autoconfigured
     * {@code ProblemDetailsExceptionHandler} (order 0, live because
     * {@code spring.mvc.problemdetails.enabled=true}) renders the
     * Framework's own RFC 9457 body. "The automatic before the manual,
     * literally per the doc's text" (compliance matrix row 7).
     */
    @Test
    @WithMockUser
    void methodNotSupportedOnBookingPath_answers405ProblemDetailByTheAutomaticHandler_a03() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                // CodeRabbit #4209499432 (resolved by documentation, the
                // documented-exception option): the framework-level statuses
                // ride the official automatic body WITHOUT the house
                // errorCode/category extensions — the contract's own
                // "documented exception" section (error-codes.md) — and the
                // pin asserts exactly that shape, not a silent absence of
                // assertions.
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.category").doesNotExist());
    }
}
