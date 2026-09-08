package com.marketplace.notifications;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationPreferenceServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private NotificationPreferenceService createService(NotificationPreferenceRepository repository,
                                                        CurrentUserProvider currentUserProvider) {
        // The dedicated REQUIRES_NEW upsert transaction rides a mocked
        // manager in unit tests: the callback runs inline, commit/rollback
        // are no-ops, and exceptions still propagate — exactly what the
        // race-retry test needs to exercise.
        return new NotificationPreferenceService(repository, currentUserProvider,
                mock(PlatformTransactionManager.class));
    }

    private CurrentUserProvider mockUser() {
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.getCurrentUserId(any(Authentication.class))).thenReturn(USER_ID);
        return provider;
    }

    @Test
    void isChannelEnabledDefaultsToTrueWhenNoOverrideExists() {
        // L22 sparse-override semantics: absence IS the default (enabled) —
        // the pre-L22 behavior (roadmap acceptance criterion 2).
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserIdAndTypeAndChannel(USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL))
                .thenReturn(Optional.empty());

        boolean enabled = createService(repository, mockUser())
                .isChannelEnabled(USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL);

        assertThat(enabled).as("no override row means the channel stays enabled").isTrue();
    }

    @Test
    void isChannelEnabledHonorsTheStoredOverride() {
        NotificationPreference override = NotificationPreference.create(
                USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false);
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserIdAndTypeAndChannel(USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(override));

        boolean enabled = createService(repository, mockUser())
                .isChannelEnabled(USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL);

        assertThat(enabled).as("an explicit override disables the channel").isFalse();
    }

    @Test
    void getMyPreferencesReturnsTheFullEffectiveMatrix() {
        // Six rows today (2 types x 3 channels) in stable order, all enabled
        // except the one stored override.
        NotificationPreference override = NotificationPreference.create(
                USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.WS, false);
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(override));

        List<NotificationPreferenceView> matrix = createService(repository, mockUser())
                .getMyPreferences(mock(Authentication.class));

        assertThat(matrix).hasSize(6);
        assertThat(matrix).containsExactly(
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.WS, false));
    }

    @Test
    void updateMyPreferencesCreatesTheOverrideWhenAbsent() {
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        // The post-upsert storage state: the override row now exists (the
        // mock's read side mirrors what the write side persisted).
        NotificationPreference stored = NotificationPreference.create(
                USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false);
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(stored));
        when(repository.findByUserIdAndTypeAndChannel(any(), any(), any())).thenReturn(Optional.empty());

        List<NotificationPreferenceView> matrix = createService(repository, mockUser())
                .updateMyPreferences(mock(Authentication.class), new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false))));

        ArgumentCaptor<NotificationPreference> saved = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.PAYMENT_STATE);
        assertThat(saved.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saved.getValue().isEnabled()).isFalse();
        assertThat(matrix).extracting(NotificationPreferenceView::enabled)
                .containsExactly(true, true, true, true, false, true);
    }

    @Test
    void updateMyPreferencesFlipsTheExistingOverride() {
        NotificationPreference existing = NotificationPreference.create(
                USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false);
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserIdAndTypeAndChannel(USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(existing));
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(existing));

        createService(repository, mockUser())
                .updateMyPreferences(mock(Authentication.class), new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, true))));

        assertThat(existing.isEnabled()).as("the existing row flips to the requested state").isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void updateMyPreferencesRejectsDuplicateSwitches() {
        // Two different values for one type x channel in one request is a
        // client bug — rejected up front instead of silent last-write-wins.
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);

        assertThatThrownBy(() -> createService(repository, mockUser())
                .updateMyPreferences(mock(Authentication.class), new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, true),
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("PAYMENT_STATE")
                .hasMessageContaining("EMAIL");
        verify(repository, never()).save(any());
    }

    @Test
    void updateMyPreferencesIsScopedToTheCallingUser() {
        // The override row carries the caller's id (self-scoped write) —
        // never a userId from the request body.
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserIdAndTypeAndChannel(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findByUserId(any())).thenReturn(List.of());

        createService(repository, mockUser())
                .updateMyPreferences(mock(Authentication.class), new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL, false))));

        ArgumentCaptor<NotificationPreference> saved = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void updateMyPreferencesRejectsTheInAppChannelOptOut() {
        // The DB (in-app) channel is always on — an opt-out would be a
        // stored state the delivery path does not honor, so it is rejected
        // before any write (CodeRabbit round 1).
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);

        assertThatThrownBy(() -> createService(repository, mockUser())
                .updateMyPreferences(mock(Authentication.class), new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.DB, false)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DB");
        verify(repository, never()).save(any());
    }

    @Test
    void updateMyPreferencesRetriesTheUpsertWhenConcurrentInsertWinsTheRace() {
        // Two concurrent PUTs can both read "no row" for the same key and
        // both insert; the unique constraint aborts the loser's inner
        // transaction (DataIntegrityViolationException). The request retries
        // once in a fresh transaction: the lookup now finds the winner's
        // committed row and the same request takes the FLIP path — exactly
        // one save (the failed insert) and the winner's row flipped to the
        // requested value.
        NotificationPreference winner = NotificationPreference.create(
                USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, true);
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserIdAndTypeAndChannel(USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL))
                .thenReturn(Optional.empty())      // the losing read
                .thenReturn(Optional.of(winner));  // the retry finds the committed winner
        when(repository.save(any(NotificationPreference.class)))
                .thenThrow(new DataIntegrityViolationException("uq_notification_preferences_user_type_channel"));
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(winner));

        List<NotificationPreferenceView> matrix = createService(repository, mockUser())
                .updateMyPreferences(mock(Authentication.class), new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false))));

        verify(repository, times(1)).save(any(NotificationPreference.class));
        assertThat(winner.isEnabled())
                .as("the retry flips the winner's row to the requested value")
                .isFalse();
        assertThat(matrix).extracting(NotificationPreferenceView::enabled)
                .containsExactly(true, true, true, true, false, true);
    }
}
