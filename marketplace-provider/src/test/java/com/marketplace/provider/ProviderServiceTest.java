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

    // -- L36: the persona fields ------------------------------------------

    @Test
    void create_fullForm_carriesThePersona() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class), eventPublisher,
                mock(com.marketplace.shared.api.ReviewStatsPort.class));
        UUID userId = UUID.randomUUID();
        when(repository.save(any(ProviderProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderProfile result = service.create("Qudsia Prime", "desc", userId,
                ProviderActorType.AGENCY, "Qudsia Prime Estates", "BR-2026-1149");

        assertThat(result.getActorType()).isEqualTo(ProviderActorType.AGENCY);
        assertThat(result.getAgencyName()).isEqualTo("Qudsia Prime Estates");
        assertThat(result.getLicenseNumber()).isEqualTo("BR-2026-1149");
    }

    @Test
    void update_fullForm_appliesPersonaSemanticsAndInvalidatesCache() {
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
                .set(field(ProviderProfile::getActorType), ProviderActorType.INDEPENDENT_BROKER)
                .create();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(profile));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        // Omitted actor type keeps the stored classification (the currency
        // rule) while the omitted display strings clear (the bio contract).
        ProviderProfile result = service.update(id, "New Name", "new bio", null, null, null,
                authentication);

        assertThat(result.getDisplayName()).isEqualTo("New Name");
        assertThat(result.getActorType()).isEqualTo(ProviderActorType.INDEPENDENT_BROKER);
        assertThat(result.getAgencyName()).isNull();
        assertThat(result.getLicenseNumber()).isNull();
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.CacheInvalidationRequested.class));
    }

    // -- L21: stored rating average ---------------------------------------

    @Test
    void refreshRatingAverage_locksRowAppliesFreshRecomputeAndInvalidatesCache() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ReviewStatsPort reviewStatsPort = mock(ReviewStatsPort.class);
        UUID reviewId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        // W1 §4.4 (the measured defect's correction): the profile row lives
        // in the profiles.id space — a DIFFERENT id from the users.id the
        // review carries (the production shape; the old flow compared the
        // two spaces and skipped silently on every real pair).
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), UUID.randomUUID())
                .set(field(ProviderProfile::getUserId), providerUserId)
                .set(field(ProviderProfile::getDisplayName), "Rated")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .set(field(ProviderProfile::getRatingAverage), null)
                .set(field(ProviderProfile::getRatingGeneralAverage), null)
                .set(field(ProviderProfile::getRatingGeneralCount), 0L)
                .create();
        assertThat(profile.getRatingAverage()).isNull();
        when(reviewStatsPort.findProviderUserIdByReviewId(reviewId))
                .thenReturn(Optional.of(providerUserId));
        when(repository.findByUserIdForUpdate(providerUserId)).thenReturn(Optional.of(profile));
        when(reviewStatsPort.findStatsByProviderId(providerUserId))
                .thenReturn(Optional.of(new ReviewStats(providerUserId, 4.5, 2)));
        when(reviewStatsPort.findGeneralStatsByProviderId(providerUserId))
                .thenReturn(Optional.empty());

        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class),
                eventPublisher, reviewStatsPort);
        service.refreshRatingAverage(reviewId);

        assertThat(profile.getRatingAverage()).isEqualTo(4.5);
        assertThat(profile.getRatingGeneralCount())
                .as("an absent general aggregate clears/keeps the exact 0 (recompute-is-truth)")
                .isZero();
        verify(repository).findByUserIdForUpdate(providerUserId);
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.CacheInvalidationRequested.class));
    }

    @Test
    void refreshRatingAverage_skipsSilentlyWhenProfileIsGone() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ReviewStatsPort reviewStatsPort = mock(ReviewStatsPort.class);
        UUID reviewId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        when(reviewStatsPort.findProviderUserIdByReviewId(reviewId))
                .thenReturn(Optional.of(providerUserId));
        when(repository.findByUserIdForUpdate(providerUserId)).thenReturn(Optional.empty());

        ProviderService service = new ProviderService(repository, mock(CurrentUserProvider.class),
                eventPublisher, reviewStatsPort);
        service.refreshRatingAverage(reviewId);

        // A deleted provider is a skip, not an exception — an exception would
        // keep the publication incomplete and retry forever.
        verify(eventPublisher, never()).publishEvent(any());
    }
}
