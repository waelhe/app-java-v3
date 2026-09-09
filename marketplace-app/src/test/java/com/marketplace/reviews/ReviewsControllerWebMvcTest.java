package com.marketplace.reviews;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = ReviewsController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class ReviewsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewsService reviewsService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ReviewMapper reviewMapper;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var review = mockReview(id);
        var response = mockResponse(id);

        when(reviewsService.getById(id)).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        mockMvc.perform(get("/api/v1/reviews/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_returnsCreated() throws Exception {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var review = mockReview(id);
        var response = mockResponse(id);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(reviewsService.create(any(), any(), any(), any())).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType("application/json")
                        .content("""
                                {"bookingId": "%s", "rating": 5}
                                """.formatted(bookingId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_withInvalidRating_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/reviews")
                        .contentType("application/json")
                        .content("""
                                {"bookingId": "%s", "rating": 99}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    // -- L21: reply ------------------------------------------------------

    @Test
    @WithMockUser(roles = "PROVIDER")
    void reply_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var review = mockReview(id);
        var response = mockResponse(id);

        when(reviewsService.reply(any(), any(), any())).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reviews/{id}/reply", id)
                        .contentType("application/json")
                        .content("""
                                {"reply": "Thanks for the feedback"}
                                """))
                .andExpect(status().isOk());
    }

    // Note: the method-security negative for reply (USER -> 403) lives in
    // ReviewsServiceSecurityTest with the REAL service bean — a @MockitoBean
    // service does not carry the @PreAuthorize into the slice, so a 403 here
    // would test the mock, not the guard (the ledger 403 pattern works because
    // its guard sits on the controller itself).

    @Test
    @WithMockUser(roles = "PROVIDER")
    void reply_withBlankBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/{id}/reply", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"reply": "  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    private static Review mockReview(UUID id) {
        var review = org.mockito.Mockito.mock(Review.class);
        when(review.getId()).thenReturn(id);
        return review;
    }

    private static ReviewResponse mockResponse(UUID id) {
        return new ReviewResponse(id, null, null, null, null, null, null, null, null);
    }

    // -- I8: the reverse review -------------------------------------------

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createReverse_returnsCreated() throws Exception {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var review = mockReview(id);
        var response = mockResponse(id);

        when(reviewsService.createReverse(any(), any(), any(), any())).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reviews/reverse")
                        .contentType("application/json")
                        .content("""
                                {"bookingId": "%s", "rating": 4}
                                """.formatted(bookingId)))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verify(reviewsService)
                .createReverse(org.mockito.ArgumentMatchers.eq(bookingId),
                        org.mockito.ArgumentMatchers.eq(4), any(), any());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createReverse_withInvalidRating_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/reverse")
                        .contentType("application/json")
                        .content("""
                                {"bookingId": "%s", "rating": 0}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listByReviewee_returnsOk() throws Exception {
        UUID consumerId = UUID.randomUUID();
        var review = mockReview(UUID.randomUUID());
        var response = mockResponse(UUID.randomUUID());

        when(reviewsService.listByReviewee(org.mockito.ArgumentMatchers.eq(consumerId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(review)));
        when(reviewMapper.toResponse(review)).thenReturn(response);

        mockMvc.perform(get("/api/v1/reviews/consumer/{consumerId}", consumerId))
                .andExpect(status().isOk());
    }
}
