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
        // Eighty-eight rows today (22 types x 4 channels — the channel
        // enum itself grew to DB/EMAIL/WS/PUSH, and the type list is
        // L35 added SAVED_SEARCH_MATCH, L42 added POST_COMMENTED, L46 added
        // NEW_LISTING_IN_NEIGHBORHOOD, L45 added CONTENT_MODERATED, L47 added
        // POST_REACTED, W4 added FOLLOWED_PROVIDER_NEW_LISTING, A-03 added
        // BOOKING_CONFIRMED, A-11 added the three order types, B-08 added
        // MESSAGE_RECEIVED, B-17 added MEMBERSHIP_VERIFIED and REPORT_RESOLVED,
        // stage 8 (ADR-0004) added the three loan decision gates, and ADR-0009
        // added the dispute pair plus LOAN_CLOSED)
        // in stable order, all enabled except the one stored override.
        NotificationPreference override = NotificationPreference.create(
                USER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.WS, false);
        NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(override));

        List<NotificationPreferenceView> matrix = createService(repository, mockUser())
                .getMyPreferences(mock(Authentication.class));

        // The union rides the full cross product: 25 types x 4 channels
        // (DB/EMAIL/WS/PUSH) = 100 rows — CI's actual output measured the
        // matrix as the enum cross product itself; the discovery wave's
        // three point additions carry the PUSH row like every other type.
        assertThat(matrix).hasSize(100);
        assertThat(matrix).containsExactly(
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CREATED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.WS, false),
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.LEAD_RECEIVED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LEAD_RECEIVED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LEAD_RECEIVED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LEAD_RECEIVED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.SAVED_SEARCH_MATCH, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.SAVED_SEARCH_MATCH, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.SAVED_SEARCH_MATCH, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.SAVED_SEARCH_MATCH, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.POST_COMMENTED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.POST_COMMENTED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.POST_COMMENTED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.POST_COMMENTED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.NEW_LISTING_IN_NEIGHBORHOOD, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.NEW_LISTING_IN_NEIGHBORHOOD, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.NEW_LISTING_IN_NEIGHBORHOOD, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.NEW_LISTING_IN_NEIGHBORHOOD, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.CONTENT_MODERATED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.CONTENT_MODERATED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.CONTENT_MODERATED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.CONTENT_MODERATED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.POST_REACTED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.POST_REACTED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.POST_REACTED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.POST_REACTED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CONFIRMED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CONFIRMED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CONFIRMED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.BOOKING_CONFIRMED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.ORDER_CONFIRMED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.ORDER_CONFIRMED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.ORDER_CONFIRMED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.ORDER_FULFILLED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.ORDER_FULFILLED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.ORDER_FULFILLED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.ORDER_FULFILLED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.ORDER_CANCELLED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.ORDER_CANCELLED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.ORDER_CANCELLED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.ORDER_CANCELLED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.MESSAGE_RECEIVED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.MESSAGE_RECEIVED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.MESSAGE_RECEIVED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.MESSAGE_RECEIVED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.MEMBERSHIP_VERIFIED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.MEMBERSHIP_VERIFIED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.MEMBERSHIP_VERIFIED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.MEMBERSHIP_VERIFIED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.REPORT_RESOLVED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.REPORT_RESOLVED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.REPORT_RESOLVED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.REPORT_RESOLVED, NotificationChannel.PUSH, true),
                // Stage 8 (ADR-0004): the three loan decision gates — default on.
                new NotificationPreferenceView(NotificationType.LOAN_REQUESTED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LOAN_REQUESTED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LOAN_REQUESTED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LOAN_REQUESTED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.LOAN_APPROVED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LOAN_APPROVED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LOAN_APPROVED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LOAN_APPROVED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.LOAN_CANCELLED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LOAN_CANCELLED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LOAN_CANCELLED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LOAN_CANCELLED, NotificationChannel.PUSH, true),
                // ADR-0009 (the dispute cycle): the freeze, the resume and
                // the settlement receipt — all three reach BOTH parties, default on.
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTE_RESOLVED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTE_RESOLVED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTE_RESOLVED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LOAN_DISPUTE_RESOLVED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.LOAN_CLOSED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.LOAN_CLOSED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.LOAN_CLOSED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.LOAN_CLOSED, NotificationChannel.PUSH, true),
                // #540 (waves D1-D4): the three point additions — URGENT_ALERT
                // (the CMP-46/JT-10 delegated-alert vocabulary) and the
                // disputes pair closing the events-without-consumers void —
                // default on, all four channels (the matrix is the full type
                // x channel cross product; CI's actual output pinned the PUSH
                // rows the earlier "no push" narrative had assumed away).
                new NotificationPreferenceView(NotificationType.URGENT_ALERT, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.URGENT_ALERT, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.URGENT_ALERT, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.URGENT_ALERT, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_OPENED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_OPENED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_OPENED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_OPENED, NotificationChannel.PUSH, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_RESOLVED, NotificationChannel.DB, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_RESOLVED, NotificationChannel.EMAIL, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_RESOLVED, NotificationChannel.WS, true),
                new NotificationPreferenceView(NotificationType.DISPUTE_RESOLVED, NotificationChannel.PUSH, true));
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
.containsExactly(                        true, true, true, true, true, false, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true, true); // The
                // full cross product: 25 types x 4 channels = 100 (stage-8's 88
                // for the 22 four-channel types + the discovery trio's rows
                // including PUSH), position 6 = the PAYMENT_STATE/EMAIL
                // override, default on everywhere else.
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
.containsExactly(                        true, true, true, true, true, false, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, true, true, true, true, true, true); // The
                // full cross product: 25 types x 4 channels = 100 (stage-8's 88
                // for the 22 four-channel types + the discovery trio's rows
                // including PUSH), position 6 = the PAYMENT_STATE/EMAIL
                // override, default on everywhere else.
    }
}
