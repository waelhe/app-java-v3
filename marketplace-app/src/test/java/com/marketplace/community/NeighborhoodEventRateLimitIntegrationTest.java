package com.marketplace.community;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L49 (the events layer) — the two event write windows' own net (the
 * NeighborhoodPostRateLimitIntegrationTest convention verbatim): the
 * module integration test pins the CONTRACT LOGIC on a generous
 * test-only budget; THIS class pins the windows themselves with tiny
 * instances — the third call inside the window answers 429 RL-001
 * problem+json, and the two instances are independent by design (a
 * spent eventCreate window leaves the eventRsvp window open — the
 * plan's own «نمطلتان مسماتان مستقلتان»).
 *
 * <p><b>The limiter-state isolation seam</b> (the posts class's own
 * round-2 lesson, verbatim): after {@code registry.remove(name)} alone,
 * the aspect's fallback re-creates the limiter from the registry's
 * DEFAULT configuration — the provable fix removes the drained instance
 * and EAGERLY re-registers a fresh one with the tiny test config
 * (computeIfAbsent semantics, measured in the 2.4.0 bytecode).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "resilience4j.ratelimiter.instances.eventCreate.limit-for-period=2",
        "resilience4j.ratelimiter.instances.eventCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.eventCreate.timeout-duration=0",
        "resilience4j.ratelimiter.instances.eventRsvp.limit-for-period=2",
        "resilience4j.ratelimiter.instances.eventRsvp.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.eventRsvp.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodEventRateLimitIntegrationTest {

    // The fixed geo seed id (R__seed_geo_qudsaya): a REAL level-3 node.
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    /**
     * The tiny window — two permits per minute, fail fast (the posts
     * class's own TINY, verbatim).
     */
    private static final io.github.resilience4j.ratelimiter.RateLimiterConfig TINY =
            io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
                    .limitForPeriod(2)
                    .limitRefreshPeriod(java.time.Duration.ofSeconds(60))
                    .timeoutDuration(java.time.Duration.ZERO)
                    .build();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    /** Delivery observation only — the gating logic under test is production code. */
    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private io.github.resilience4j.ratelimiter.RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @Autowired
    private NeighborhoodEventService eventService;

    private UUID organizerId;

    @BeforeEach
    void seed() {
        // Full windows for every test (the eager re-registration seam).
        rateLimiterRegistry.remove("eventCreate");
        rateLimiterRegistry.rateLimiter("eventCreate", TINY);
        rateLimiterRegistry.remove("eventRsvp");
        rateLimiterRegistry.rateLimiter("eventRsvp", TINY);
        // Cross-test isolation (the module class's own convention).
        jdbc.update("DELETE FROM event_rsvps");
        jdbc.update("DELETE FROM neighborhood_events");
        // A REAL member of the REAL seed node — the membership gate must
        // not mask the limiter's own answer.
        organizerId = UUID.randomUUID();
        membershipService.join(organizerId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(organizerId);
    }

    private String eventBody() {
        return "{\"locationId\": \"" + QUDSAYYA_OLD_TOWN + "\", \"category\": \"SOCIAL\", "
                + "\"title\": \"T\", \"description\": \"D\", "
                + "\"startsAt\": \"2027-03-02T08:00:00Z\", "
                + "\"locationLabel\": \"Spot\", \"organizerLabel\": \"O\", "
                + "\"registration\": \"OPEN\"}";
    }

    @Test
    @DisplayName("eventCreate: the third event inside the window answers 429 RL-001 problem+json")
    void eventCreate_thirdCallIsRateLimited() throws Exception {
        // First two: the business logic itself answers (201).
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(eventBody()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(eventBody()))
                .andExpect(status().isCreated());

        // Third: rejected BEFORE any business code runs — the documented
        // ProblemDetail contract (RL-001, RFC 7807 media type).
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(eventBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RL-001"));
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_events WHERE author_id = ?",
                Integer.class, organizerId);
        org.assertj.core.api.Assertions.assertThat(events).isEqualTo(2);
    }

    @Test
    @DisplayName("eventRsvp: the third seat inside the window answers 429 RL-001 problem+json")
    void eventRsvp_thirdCallIsRateLimited() throws Exception {
        // An open event to seat on (the service call bypasses the
        // eventCreate controller annotation entirely — only eventRsvp's
        // window is under test here).
        var ownEvent = eventService.createEvent(organizerId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                EventCategory.SOCIAL, "T", "D",
                java.time.Instant.parse("2027-03-02T08:00:00Z"), null,
                "Spot", "O", null, EventRegistration.OPEN);

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", ownEvent.id()).with(jwt()))
                .andExpect(status().isCreated());
        // The second call FREES the first seat (the toggle's two
        // directions ride ONE instance — the postReact model) and costs
        // the same window.
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", ownEvent.id()).with(jwt()))
                .andExpect(status().isConflict());

        // Third: the window is spent.
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", ownEvent.id()).with(jwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RL-001"));
        Integer seats = jdbc.queryForObject(
                "SELECT count(*) FROM event_rsvps WHERE member_id = ?",
                Integer.class, organizerId);
        org.assertj.core.api.Assertions.assertThat(seats).isEqualTo(1);
    }

    @Test
    @DisplayName("the two windows are independent: a spent create window leaves the rsvp window open")
    void theTwoWindowsAreIndependent() throws Exception {
        // Spend the eventCreate window completely (two creates + the
        // rejected third).
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(eventBody()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(eventBody()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(eventBody()))
                .andExpect(status().isTooManyRequests());

        // The eventRsvp window is untouched by the spent create window —
        // the seat answers 201 through it.
        var seeded = jdbc.queryForObject(
                "SELECT id FROM neighborhood_events WHERE author_id = ? LIMIT 1",
                java.util.UUID.class, organizerId);
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded).with(jwt()))
                .andExpect(status().isCreated());
    }
}
