package com.marketplace.messaging;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.TooManyRequestsException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L34 (realestate systems plan §5 — lead capture): the service's gates in
 * isolation — the liveness disambiguation (409 vs 404), the G-R6 daily
 * cap, the after-commit event, and the inbox's ownership scoping.
 */
@ExtendWith(MockitoExtension.class)
class LeadsServiceTest {

    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final String IP = "203.0.113.7";
    private static final String IP_HASH = LeadsService.hashIp("test-hmac-key", IP);

    @Mock
    private ListingLeadRepository leadRepository;

    @Mock
    private CatalogSpi catalogSpi;

    @Mock
    private ListingPriceProvider listingPriceProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private MessagingProperties properties;

    @InjectMocks
    private LeadsService service;

    private LeadRequest request() {
        return new LeadRequest("Sami Ahmad", "+963991234567", "Is it still available?");
    }

    private void activeListing() {
        when(catalogSpi.getActiveById(LISTING_ID))
                .thenReturn(new ProviderListingView(LISTING_ID, "Title", "Desc", "APARTMENT",
                        100_00L, "SAR", PROVIDER_ID, "ACTIVE", null, Instant.now(), Instant.now()));
    }

    @Test
    void createLeadHappyPathPublishesEventAndAttributesSender() {
        activeListing();
        when(currentUserProvider.tryGetCurrentUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(leadRepository.save(any(ListingLead.class))).thenAnswer(inv -> inv.getArgument(0));
        when(properties.leads()).thenReturn(new MessagingProperties.Leads(5, "test-hmac-key"));

        LeadResponse response = service.createLead(LISTING_ID, request(), null, IP);

        assertThat(response.status()).isEqualTo("NEW");
        ArgumentCaptor<ListingLead> saved = ArgumentCaptor.forClass(ListingLead.class);
        verify(leadRepository).save(saved.capture());
        assertThat(saved.getValue().getSenderIpHash()).isEqualTo(IP_HASH);
        assertThat(saved.getValue().getProviderId()).isEqualTo(PROVIDER_ID);

        ArgumentCaptor<ListingLeadCreatedEvent> event =
                ArgumentCaptor.forClass(ListingLeadCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().providerId()).isEqualTo(PROVIDER_ID);
        assertThat(event.getValue().listingId()).isEqualTo(LISTING_ID);
    }

    @Test
    void createLeadOnNonActiveListingIs409Not404() {
        // The plan's acceptance criterion 2: the lead rides live inventory
        // only — an existing-but-inactive listing is a 409, not a 404.
        when(catalogSpi.getActiveById(LISTING_ID))
                .thenThrow(new ResourceNotFoundException("Listing", LISTING_ID));
        when(listingPriceProvider.getListingInfo(LISTING_ID)).thenReturn(
                new ListingPriceProvider.ListingInfo(PROVIDER_ID, 100_00L, "SAR"));

        assertThatThrownBy(() -> service.createLead(LISTING_ID, request(), null, IP))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not active");
        verify(leadRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createLeadOnMissingListingIs404() {
        when(catalogSpi.getActiveById(LISTING_ID))
                .thenThrow(new ResourceNotFoundException("Listing", LISTING_ID));
        when(listingPriceProvider.getListingInfo(LISTING_ID))
                .thenThrow(new ResourceNotFoundException("Listing", LISTING_ID));

        assertThatThrownBy(() -> service.createLead(LISTING_ID, request(), null, IP))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void dailyCapExhaustionRejectsWith429() {
        // G-R6: the conservative initial cap — the sixth submission from
        // the same fingerprint inside the window is a
        // TooManyRequestsException (the house RL-001 taxonomy entry).
        activeListing();
        when(currentUserProvider.tryGetCurrentUserId(any())).thenReturn(Optional.empty());
        when(properties.leads()).thenReturn(new MessagingProperties.Leads(5, "test-hmac-key"));
        when(leadRepository.countBySenderIpHashAndCreatedAtAfter(eq(IP_HASH), any(Instant.class)))
                .thenReturn(5L);

        assertThatThrownBy(() -> service.createLead(LISTING_ID, request(), null, IP))
                .isInstanceOf(TooManyRequestsException.class);
        verify(leadRepository, never()).save(any());
    }

    @Test
    void absentClientIpSkipsTheDailyCap() {
        // No address (a test seam / a channel without one): no fingerprint,
        // no cap — the global rate limiter is the only guard.
        activeListing();
        when(currentUserProvider.tryGetCurrentUserId(any())).thenReturn(Optional.empty());
        when(properties.leads()).thenReturn(new MessagingProperties.Leads(5, "test-hmac-key"));
        when(leadRepository.save(any(ListingLead.class))).thenAnswer(inv -> inv.getArgument(0));
        // The cap block is entered (the key is read) but the count is not:
        // the absent IP skips both the lock and the window query.

        LeadResponse response = service.createLead(LISTING_ID, request(), null, null);

        assertThat(response.status()).isEqualTo("NEW");
        verify(leadRepository, never()).countBySenderIpHashAndCreatedAtAfter(anyString(), any());
    }

    @Test
    void inboxListsAndTransitionsOwnLeads() {
        // The lead's provider column lives in the users.id space (A1/V2) —
        // the owner key IS the user id, identity-scoped.
        ListingLead lead = ListingLead.create(LISTING_ID, PROVIDER_ID, null, IP_HASH,
                "Sami", "+963991234567", "hello");
        when(leadRepository.findByIdAndProviderId(lead.getId(), PROVIDER_ID))
                .thenReturn(Optional.of(lead));

        LeadResponse moved = service.transitionLead(lead.getId(), PROVIDER_ID, LeadStatus.READ);

        assertThat(moved.status()).isEqualTo("READ");
    }

    @Test
    void foreignProvidersLeadIs404NotHis() {
        // A foreign provider's lead "does not exist" in your inbox — the
        // repository read is scoped to the owner.
        UUID foreignLeadId = UUID.randomUUID();
        when(leadRepository.findByIdAndProviderId(foreignLeadId, PROVIDER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transitionLead(foreignLeadId, PROVIDER_ID, LeadStatus.READ))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void inboxReadDelegatesWithDeterministicOrder() {
        ListingLead lead = ListingLead.create(LISTING_ID, PROVIDER_ID, null, null,
                "Sami", "+963991234567", "hello");
        Pageable any = PageRequest.of(0, 20);
        when(leadRepository.findByProviderId(eq(PROVIDER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(lead)));

        var page = service.listLeads(PROVIDER_ID, null, any);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().status()).isEqualTo("NEW");
    }
}
