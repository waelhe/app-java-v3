package com.marketplace.reviews;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewsControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final ReviewsService reviewsService = mock(ReviewsService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new ReviewsController(reviewsService, currentUserProvider))
            .setApiVersionStrategy(apiVersionStrategy())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    private final UUID reviewId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    private static void asConsumer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("consumer", "",
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    @Test
    void getById() throws Exception {
        when(reviewsService.getById(any())).thenReturn(mock(Review.class));

        mockMvc.perform(get("/api/v1/reviews/{id}", reviewId))
                .andExpect(status().isOk());
    }

    @Test
    void listByProvider() throws Exception {
        when(reviewsService.listByProvider(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/reviews/provider/{providerId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listByReviewer() throws Exception {
        when(reviewsService.listByReviewer(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/reviews/reviewer/{reviewerId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void create() throws Exception {
        asConsumer();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(reviewsService.create(any(), any(), any(), any())).thenReturn(mock(Review.class));

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType("application/json")
                        .content("{\"bookingId\": \"" + UUID.randomUUID() + "\", \"rating\": 5}"))
                .andExpect(status().isCreated());
    }

    @Test
    void update() throws Exception {
        asConsumer();
        when(reviewsService.update(any(), any(), any(), any())).thenReturn(mock(Review.class));

        mockMvc.perform(put("/api/v1/reviews/{id}", reviewId)
                        .contentType("application/json")
                        .content("{\"rating\": 4}"))
                .andExpect(status().isOk());
    }

    @Test
    void create_validationError() throws Exception {
        asConsumer();
        mockMvc.perform(post("/api/v1/reviews")
                        .contentType("application/json")
                        .content("{\"rating\": 6}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_validationError() throws Exception {
        asConsumer();
        mockMvc.perform(put("/api/v1/reviews/{id}", reviewId)
                        .contentType("application/json")
                        .content("{\"rating\": 0}"))
                .andExpect(status().isBadRequest());
    }
}
