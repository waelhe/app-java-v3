package com.marketplace.reviews;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = ReviewsController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
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

    private static Review mockReview(UUID id) {
        var review = org.mockito.Mockito.mock(Review.class);
        when(review.getId()).thenReturn(id);
        return review;
    }

    private static ReviewResponse mockResponse(UUID id) {
        return new ReviewResponse(id, null, null, null, null, null);
    }
}
