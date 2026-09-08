package com.marketplace.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L29 (feature-expansion roadmap §5, Week 4): the 429 contract of the three
 * newly covered public WRITE endpoints — booking creation, review creation,
 * media upload requests.
 *
 * <p>Each endpoint carries an INDEPENDENT named rate-limiter instance
 * (bookingCreate / reviewCreate / mediaUpload — application.yml), so the
 * class pins three facts per endpoint: (1) the first well-formed call runs
 * the business logic (its own domain answer — 404/503 here, never a limiter
 * artifact), (2) the next call inside the refresh window is rejected with
 * 429 BEFORE any business code runs, and (3) the rejection carries the
 * documented ProblemDetail contract (RL-001, rate-limit category, RFC 7807
 * media type) — the same contract OpenApiConfig documents globally.
 *
 * <p>The instances are shrunk to {@code limit-for-period=1} with
 * {@code timeout-duration=0} via test properties — the same official
 * Resilience4j Spring Boot binding channel production uses (relaxed binding
 * from yml/env). Production values are never load-bearing in tests (G8
 * calibration would otherwise break them); the timeout 0 matches the
 * fail-fast production design for write endpoints (no queueing).
 *
 * <p>Request bodies are well-formed on purpose: {@code @Valid} rejects
 * malformed bodies during argument resolution — BEFORE the controller
 * method (and the limiter) runs — so an invalid first call would consume
 * no permission. Only valid calls count against the limit; that is the
 * aspect's official semantics (the AOP interceptor wraps the method
 * invocation).
 */
@SpringBootTest(properties = {
        "resilience4j.ratelimiter.instances.bookingCreate.limit-for-period=1",
        "resilience4j.ratelimiter.instances.bookingCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.bookingCreate.timeout-duration=0",
        "resilience4j.ratelimiter.instances.reviewCreate.limit-for-period=1",
        "resilience4j.ratelimiter.instances.reviewCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.reviewCreate.timeout-duration=0",
        "resilience4j.ratelimiter.instances.mediaUpload.limit-for-period=1",
        "resilience4j.ratelimiter.instances.mediaUpload.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.mediaUpload.timeout-duration=0"
})
@ActiveProfiles("test")
class RateLimitProblemDetailIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("bookingCreate: second call inside the window answers 429 RL-001 problem+json")
    @WithMockUser(roles = "CONSUMER")
    void bookingCreate_secondCallIsRateLimited() throws Exception {
        String body = """
                {
                  "listingId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                  "startsAt": "2026-10-01T14:00:00Z",
                  "endsAt": "2026-10-04T10:00:00Z",
                  "notes": "rate limit contract test"
                }
                """;

        // First call: the business logic itself answers (unknown listing → 404
        // NF-001) — the limiter must not mask the domain answer.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andDo(print()) // TEMP: capture the first-call response for the 400 diagnosis
                .andExpect(status().isNotFound());

        // Second call inside the 60s window: limiter rejection BEFORE any
        // business code, with the documented problem contract.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Rate limit exceeded. Please try again later."))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings"))
                .andExpect(jsonPath("$.errorCode").value("RL-001"))
                .andExpect(jsonPath("$.category").value("rate-limit"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().string("X-Correlation-ID", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    @DisplayName("reviewCreate: second call inside the window answers 429 RL-001 problem+json")
    @WithMockUser(roles = "CONSUMER")
    void reviewCreate_secondCallIsRateLimited() throws Exception {
        String body = """
                {
                  "bookingId": "0d3b3f7e-1f2a-4c3b-9c2d-5e6f7a8b9c0d",
                  "rating": 5,
                  "comment": "rate limit contract test"
                }
                """;

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andDo(print()) // TEMP: capture the first-call response for the 400 diagnosis
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.errorCode").value("RL-001"))
                .andExpect(jsonPath("$.category").value("rate-limit"))
                .andExpect(jsonPath("$.instance").value("/api/v1/reviews"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("mediaUpload: second call inside the window answers 429 RL-001 problem+json")
    @WithMockUser(roles = "PROVIDER")
    void mediaUpload_secondCallIsRateLimited() throws Exception {
        String body = """
                {
                  "listingId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                  "contentType": "image/jpeg",
                  "sizeBytes": 418381
                }
                """;

        // First call: storage is unconfigured in the test profile → the
        // documented 503 SU-001 (the module's inert-by-design answer).
        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.errorCode").value("RL-001"))
                .andExpect(jsonPath("$.category").value("rate-limit"))
                .andExpect(jsonPath("$.instance").value("/api/v1/media/uploads"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
