package com.marketplace.app.booking;

import com.marketplace.booking.Booking;
import com.marketplace.booking.BookingController;
import com.marketplace.booking.BookingMapper;
import com.marketplace.booking.BookingService;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation;
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.DefaultApiVersionStrategy;
import org.springframework.web.accept.HeaderApiVersionResolver;
import org.springframework.web.accept.SemanticApiVersionParser;

import org.instancio.Instancio;
import java.util.List;
import java.util.UUID;

import static org.instancio.Select.field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith({MockitoExtension.class, RestDocumentationExtension.class})
class BookingApiDocumentationTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private BookingMapper bookingMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        var strategy = new DefaultApiVersionStrategy(
                List.of(new HeaderApiVersionResolver("X-API-Version")),
                new SemanticApiVersionParser(),
                false, "1.0", false, null, null);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BookingController(bookingService, currentUserProvider, bookingMapper))
                .setApiVersionStrategy(strategy)
                .apply(MockMvcRestDocumentation.documentationConfiguration(restDocumentation))
                .build();
    }

    @Test
    void shouldGenerateBookingApiDocs() throws Exception {
        UUID id = Instancio.create(UUID.class);
        var booking = Instancio.of(Booking.class)
                .set(field(Booking::getId), id)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "Test notes")
                .create();
        when(bookingService.getByIdForUser(any(), any())).thenReturn(booking);

        mockMvc.perform(RestDocumentationRequestBuilders.get("/api/v1/bookings/{id}", id)
                        .header("X-API-Version", "1.0"))
                .andExpect(status().isOk())
                .andDo(MockMvcRestDocumentation.document("booking/get-booking",
                        preprocessResponse(prettyPrint())));
    }
}
