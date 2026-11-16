package com.marketplace.provider.spi;

import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import com.marketplace.provider.ProviderStatus;
import com.marketplace.shared.api.ProviderFulfillmentLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderFulfillmentAdapterTest {

    @Mock
    private ProviderRepository providerRepository;

    @Mock
    private ProviderProfile profile;

    private ProviderFulfillmentAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ProviderFulfillmentAdapter(providerRepository);
    }

    @Test
    void returnsOnlyTheCompletePrivatePickupPointOfAVerifiedProvider() {
        UUID userId = UUID.randomUUID();
        when(providerRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profile.getStatus()).thenReturn(ProviderStatus.VERIFIED);
        when(profile.getUserId()).thenReturn(userId);
        when(profile.getFulfillmentAddress()).thenReturn("Seller depot");
        when(profile.getFulfillmentLatitude()).thenReturn(37.0);
        when(profile.getFulfillmentLongitude()).thenReturn(-122.0);

        assertThat(adapter.findVerifiedLocation(userId))
                .contains(new ProviderFulfillmentLocation(userId, "Seller depot", 37.0, -122.0));
    }

    @Test
    void returnsEmptyWhenTheProviderProfileDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(providerRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(adapter.findVerifiedLocation(userId)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheProviderIsNotVerified() {
        UUID userId = UUID.randomUUID();
        when(providerRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profile.getStatus()).thenReturn(ProviderStatus.PENDING);

        assertThat(adapter.findVerifiedLocation(userId)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheVerifiedProfileHasIncompletePickupCoordinates() {
        UUID userId = UUID.randomUUID();
        when(providerRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profile.getStatus()).thenReturn(ProviderStatus.VERIFIED);
        when(profile.getFulfillmentAddress()).thenReturn("Seller depot");
        when(profile.getFulfillmentLatitude()).thenReturn(37.0);
        when(profile.getFulfillmentLongitude()).thenReturn(null);

        assertThat(adapter.findVerifiedLocation(userId)).isEmpty();
    }
}
