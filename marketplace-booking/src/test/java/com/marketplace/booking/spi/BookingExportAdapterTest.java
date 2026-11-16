package com.marketplace.booking.spi;

import com.marketplace.booking.Booking;
import com.marketplace.booking.BookingRepository;
import com.marketplace.shared.api.BookingExportEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingExportAdapterTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final BookingExportAdapter adapter = new BookingExportAdapter(bookingRepository);

    @Test
    void exportsBothSidesWithCounterpartyAsOpaqueUuidAndHisRole() {
        UUID me = UUID.randomUUID();
        UUID otherProvider = UUID.randomUUID();
        UUID otherConsumer = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        // One booking where I am the consumer, one where I am the provider.
        Booking asConsumer = Booking.create(me, otherProvider, listing,
                10_000L, "SAR", null, null, null);
        Booking asProvider = Booking.create(otherConsumer, me, listing,
                5_000L, "SAR", null, null, null);
        when(bookingRepository.findAllByConsumerIdOrProviderIdOrderByCreatedAtAscIdAsc(me, me))
                .thenReturn(List.of(asConsumer, asProvider));

        List<BookingExportEntry> entries = adapter.exportForParticipant(me);

        assertEquals(2, entries.size());

        BookingExportEntry consumerSide = entries.get(0);
        assertEquals("CONSUMER", consumerSide.role());
        assertEquals(otherProvider, consumerSide.counterpartyId());
        assertEquals(10_000L, consumerSide.priceCents());
        assertEquals("SAR", consumerSide.currency());
        assertEquals("PENDING", consumerSide.status());
        assertEquals(asConsumer.getId(), consumerSide.id());
        assertEquals(listing, consumerSide.listingId());

        BookingExportEntry providerSide = entries.get(1);
        assertEquals("PROVIDER", providerSide.role());
        assertEquals(otherConsumer, providerSide.counterpartyId());
        assertEquals(5_000L, providerSide.priceCents());
    }

    @Test
    void theEntryContractCarriesThePlansEnumerationOnly() {
        // The plan's booking enumeration is status/dates/amounts: the entry
        // record has exactly the fields the contract fixes — no notes, no
        // audit columns (compile-time shape, asserted so a field addition
        // must consciously re-open this test).
        assertEquals(11, BookingExportEntry.class.getRecordComponents().length);
        // Dates the JPA auditor never set in a unit test map as null —
        // the mapping passes the entity's own values through untouched.
        UUID me = UUID.randomUUID();
        Booking booking = Booking.create(me, UUID.randomUUID(), UUID.randomUUID(),
                1_000L, "SAR", null, null, "notes stay out");
        when(bookingRepository.findAllByConsumerIdOrProviderIdOrderByCreatedAtAscIdAsc(me, me))
                .thenReturn(List.of(booking));

        BookingExportEntry entry = adapter.exportForParticipant(me).get(0);

        assertNull(entry.startsAt());
        assertNull(entry.endsAt());
        assertNull(entry.createdAt());
        assertNull(entry.updatedAt());
    }
}
