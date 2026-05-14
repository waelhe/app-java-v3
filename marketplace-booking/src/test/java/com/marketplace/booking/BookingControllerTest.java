package com.marketplace.booking;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final BookingService bookingService = mock(BookingService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new BookingController(bookingService, currentUserProvider))
            .setApiVersionStrategy(apiVersionStrategy())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    private static Booking aBooking() {
        return new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1000L, null);
    }

    private static void asConsumer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("consumer", "",
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    private static void asProvider() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("provider", "",
                        List.of(new SimpleGrantedAuthority("ROLE_PROVIDER"))));
    }

    @Test
    void getById() throws Exception {
        when(bookingService.getByIdForUser(any(), any())).thenReturn(aBooking());

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                .andExpect(status().isOk());
    }

    @Test
    void listByConsumer() throws Exception {
        when(bookingService.listByConsumer(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/bookings/consumer/{consumerId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listByProvider() throws Exception {
        when(bookingService.listByProvider(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/bookings/provider/{providerId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void create() throws Exception {
        asConsumer();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(bookingService.create(any(), any(), any())).thenReturn(aBooking());

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/json")
                        .content("{\"listingId\": \"" + listingId + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void create_validationError() throws Exception {
        asConsumer();
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancel() throws Exception {
        asConsumer();
        when(bookingService.cancel(any(), any())).thenReturn(aBooking());

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId))
                .andExpect(status().isOk());
    }

    @Test
    void confirm() throws Exception {
        asProvider();
        when(bookingService.confirm(any(), any())).thenReturn(aBooking());

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId))
                .andExpect(status().isOk());
    }

    @Test
    void complete() throws Exception {
        asProvider();
        when(bookingService.complete(any(), any())).thenReturn(aBooking());

        mockMvc.perform(post("/api/v1/bookings/{id}/complete", bookingId))
                .andExpect(status().isOk());
    }
}
