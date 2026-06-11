package com.marketplace.booking;

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
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = BookingController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class BookingControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private BookingMapper bookingMapper;

    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_returnsCreated() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var booking = mockBooking(bookingId);
        var response = mockResponse(bookingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(bookingService.create(any(), any(), any())).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/json")
                        .content("""
                                {"listingId": "%s"}
                                """.formatted(listingId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void confirm_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var booking = mockBooking(id);
        var response = mockResponse(id);

        when(bookingService.confirm(any(), any())).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void complete_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var booking = mockBooking(id);
        var response = mockResponse(id);

        when(bookingService.complete(any(), any())).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings/{id}/complete", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void cancel_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var booking = mockBooking(id);
        var response = mockResponse(id);

        when(bookingService.cancel(any(), any())).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var booking = mockBooking(id);
        var response = mockResponse(id);

        when(bookingService.getByIdForUser(any(), any())).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        mockMvc.perform(get("/api/v1/bookings/{id}", id))
                .andExpect(status().isOk());
    }

    private static Booking mockBooking(UUID id) {
        var booking = org.mockito.Mockito.mock(Booking.class);
        when(booking.getId()).thenReturn(id);
        return booking;
    }

    private static BookingResponse mockResponse(UUID id) {
        return new BookingResponse(id, null, null, null, null, null);
    }
}
