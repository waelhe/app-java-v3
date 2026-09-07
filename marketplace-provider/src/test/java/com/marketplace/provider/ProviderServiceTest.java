package com.marketplace.provider;

import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.*;

class ProviderServiceTest {

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    @Test
    void verifyChangesStatusToVerified() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Provider A")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(java.util.Optional.of(profile));

        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class), eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        ProviderProfile verified = service.verify(profile.getId());

        assertThat(verified.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
    }

    @Test
    void suspendChangesStatusToSuspended() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Provider B")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(java.util.Optional.of(profile));

        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class), eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        ProviderProfile suspended = service.suspend(profile.getId());

        assertThat(suspended.getStatus()).isEqualTo(ProviderStatus.SUSPENDED);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class), eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        UUID unknownId = Instancio.create(UUID.class);
        when(repository.findById(unknownId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getById(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_savesAndReturnsProfile() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class), eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        UUID userId = UUID.randomUUID();
        when(repository.save(any(ProviderProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderProfile result = service.create("New Provider", "desc", userId);

        assertThat(result.getDisplayName()).isEqualTo("New Provider");
        assertThat(result.getBio()).isEqualTo("desc");
        assertThat(result.getStatus()).isEqualTo(ProviderStatus.PENDING);
        assertThat(result.getUserId()).isEqualTo(userId);
    }

    @Test
    void getById_returnsProfileWhenFound() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class), eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        UUID id = Instancio.create(UUID.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), id)
                .set(field(ProviderProfile::getDisplayName), "Found")
                .create();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(profile));

        ProviderProfile result = service.getById(id);

        assertThat(result.getDisplayName()).isEqualTo("Found");
    }

    @Test
    void update_changesDisplayNameAndBio() {
        ProviderRepository repository = mock(ProviderRepository.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        Authentication authentication = mock(Authentication.class);
        ProviderService service = new ProviderService(repository, currentUserProvider, eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        UUID id = Instancio.create(UUID.class);
        UUID userId = UUID.randomUUID();
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), id)
                .set(field(ProviderProfile::getDisplayName), "Old")
                .set(field(ProviderProfile::getBio), "old bio")
                .set(field(ProviderProfile::getUserId), userId)
                .create();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(profile));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        ProviderProfile result = service.update(id, "New Name", "new bio", authentication);

        assertThat(result.getDisplayName()).isEqualTo("New Name");
        assertThat(result.getBio()).isEqualTo("new bio");
    }

    // -- L21: stored rating average ---------------------------------------

    @Test
    void refreshRatingAverage_locksRowAppliesFreshRecomputeAndInvalidatesCache() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ReviewStatsPort reviewStatsPort = mock(ReviewStatsPort.class);
        UUID reviewId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        // The profile's id IS the provider id the listener resolves — Instancio
        // sets it because the factory does not take an id.
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), providerId)
                .set(field(ProviderProfile::getDisplayName), "Rated")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .set(field(ProviderProfile::getRatingAverage), null)
                .create();
        assertThat(profile.getRatingAverage()).isNull();
        // The initial resolution (review -> provider) may see a stale snapshot;
        // the recompute INSIDE the lock is what lands.
        when(reviewStatsPort.findStatsByReviewId(reviewId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 3.0, 1)));
        when(repository.findByIdForUpdate(providerId)).thenReturn(Optional.of(profile));
        when(reviewStatsPort.findStatsByProviderId(providerId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.5, 2)));

        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class),
                eventPublisher, reviewStatsPort);
        service.refreshRatingAverage(reviewId);

        assertThat(profile.getRatingAverage()).isEqualTo(4.5);
        verify(repository).findByIdForUpdate(providerId);
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.CacheInvalidationRequested.class));
    }

    @Test
    void refreshRatingAverage_skipsSilentlyWhenProfileIsGone() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ReviewStatsPort reviewStatsPort = mock(ReviewStatsPort.class);
        UUID reviewId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(reviewStatsPort.findStatsByReviewId(reviewId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 3.0, 1)));
        when(repository.findByIdForUpdate(providerId)).thenReturn(Optional.empty());

        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class),
                eventPublisher, reviewStatsPort);
        service.refreshRatingAverage(reviewId);

        // A deleted provider is a skip, not an exception — an exception would
        // keep the publication incomplete and retry forever.
        verify(eventPublisher, never()).publishEvent(any());
    }
}
