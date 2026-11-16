package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationChannel;
import com.marketplace.notifications.NotificationTopicPreferenceView;
import com.marketplace.notifications.NotificationTopicPreferenceUpdate;
import com.marketplace.notifications.NotificationTopicPreferencesUpdateRequest;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 (§8.1) — the hierarchical topic-preference service at the unit
 * seam (the #545 measured coverage gap: the second resolution level of
 * the send gate had no service test). The contracts proven here:
 * <ul>
 *   <li>the DEFAULT matrix — no stored row means every topic × EMAIL/WS
 *       answers enabled, in stable (topic, channel) order;</li>
 *   <li>the stored override merges into that matrix and into the
 *       engine's {@code findChannelOverride} lookup (empty for any
 *       channel outside the governed pair — the in-app channel is always
 *       on);</li>
 *   <li>the PUT upsert — create the absent row, flip the existing one,
 *       reject a duplicated key and any switch outside EMAIL/WS;</li>
 *   <li>the L22 lost-insert-race retry — a concurrent duplicate INSERT
 *       loses, the fresh-transaction retry takes the flip path on the
 *       winner's committed row (value-idempotent), and only ONE save is
 *       ever attempted.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotificationTopicPreferenceServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private NotificationTopicPreferenceRepository repository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private NotificationTopicPreferenceService service() {
        // The REQUIRES_NEW upsert template over a mocked manager: the
        // callback runs inline — the transactional shape itself is the
        // L22 pattern already proven by the integration tests.
        return new NotificationTopicPreferenceService(repository, currentUserProvider,
                mock(PlatformTransactionManager.class));
    }

    private void callerIsTheUser() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER);
    }

    @Test
    void getMyTopicPreferences_answersTheEnabledDefaultMatrixWhenNothingIsStored() {
        callerIsTheUser();
        when(repository.findByUserId(USER)).thenReturn(List.of());

        List<NotificationTopicPreferenceView> matrix = service().getMyTopicPreferences(null);

        assertThat(matrix).hasSize(NotificationTopic.values().length * 2);
        assertThat(matrix).allMatch(view -> view.enabled());
        // Stable (topic, channel) order — EMAIL precedes WS inside every topic.
        assertThat(matrix.get(0).topic()).isEqualTo(NotificationTopic.values()[0]);
        assertThat(matrix.get(0).channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(matrix.get(1).channel()).isEqualTo(NotificationChannel.WS);
    }

    @Test
    void getMyTopicPreferences_mergesTheStoredOverridesIntoTheMatrix() {
        callerIsTheUser();
        NotificationTopicPreference stored = NotificationTopicPreference.create(USER,
                NotificationTopic.COMMUNITY, NotificationChannel.EMAIL, false);
        when(repository.findByUserId(USER)).thenReturn(List.of(stored));

        List<NotificationTopicPreferenceView> matrix = service().getMyTopicPreferences(null);

        assertThat(matrix).hasSize(NotificationTopic.values().length * 2);
        assertThat(matrix.stream()
                .filter(v -> v.topic() == NotificationTopic.COMMUNITY
                        && v.channel() == NotificationChannel.EMAIL)
                .toList())
                .singleElement()
                .satisfies(v -> assertThat(v.enabled()).isFalse());
        assertThat(matrix.stream()
                .filter(v -> v.topic() == NotificationTopic.COMMUNITY
                        && v.channel() == NotificationChannel.WS)
                .toList())
                .singleElement()
                .satisfies(v -> assertThat(v.enabled()).isTrue());
    }

    @Test
    void findChannelOverride_answersEmptyForTheUngovernedChannel() {
        // The in-app channel is always on — the engine's topic level must
        // never answer a stored switch for it.
        Optional<Boolean> override = service().findChannelOverride(USER,
                NotificationTopic.COMMUNITY, NotificationChannel.DB);

        assertThat(override).isEmpty();
        verify(repository, never()).findByUserIdAndTopicAndChannel(any(), any(), any());
    }

    @Test
    void findChannelOverride_answersTheStoredSwitch() {
        when(repository.findByUserIdAndTopicAndChannel(USER, NotificationTopic.COMMUNITY,
                NotificationChannel.EMAIL))
                .thenReturn(Optional.of(NotificationTopicPreference.create(USER,
                        NotificationTopic.COMMUNITY, NotificationChannel.EMAIL, false)));

        assertThat(service().findChannelOverride(USER, NotificationTopic.COMMUNITY,
                NotificationChannel.EMAIL)).contains(false);
    }

    @Test
    void updateMyTopicPreferences_createsTheAbsentOverrideAndReturnsTheMatrix() {
        callerIsTheUser();
        when(repository.findByUserIdAndTopicAndChannel(USER, NotificationTopic.COMMUNITY,
                NotificationChannel.EMAIL)).thenReturn(Optional.empty());
        when(repository.findByUserId(USER)).thenReturn(List.of());

        List<NotificationTopicPreferenceView> matrix = service().updateMyTopicPreferences(null,
                request(new NotificationTopicPreferenceUpdate(NotificationTopic.COMMUNITY,
                        NotificationChannel.EMAIL, false)));

        ArgumentCaptor<NotificationTopicPreference> saved =
                ArgumentCaptor.forClass(NotificationTopicPreference.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getTopic()).isEqualTo(NotificationTopic.COMMUNITY);
        assertThat(saved.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saved.getValue().isEnabled()).isFalse();
        assertThat(matrix).hasSize(NotificationTopic.values().length * 2);
    }

    @Test
    void updateMyTopicPreferences_flipsTheExistingOverride() {
        callerIsTheUser();
        NotificationTopicPreference existing = NotificationTopicPreference.create(USER,
                NotificationTopic.COMMUNITY, NotificationChannel.EMAIL, true);
        when(repository.findByUserIdAndTopicAndChannel(USER, NotificationTopic.COMMUNITY,
                NotificationChannel.EMAIL)).thenReturn(Optional.of(existing));
        when(repository.findByUserId(USER)).thenReturn(List.of());

        service().updateMyTopicPreferences(null,
                request(new NotificationTopicPreferenceUpdate(NotificationTopic.COMMUNITY,
                        NotificationChannel.EMAIL, false)));

        verify(repository, never()).save(any());
        assertThat(existing.isEnabled()).isFalse();
    }

    @Test
    void updateMyTopicPreferences_rejectsADuplicatedSwitch() {
        // Two values for one switch in one request is a client bug — the
        // upsert must never guess which one wins.
        assertThatThrownBy(() -> service().updateMyTopicPreferences(null,
                request(new NotificationTopicPreferenceUpdate(NotificationTopic.COMMUNITY,
                                NotificationChannel.EMAIL, false),
                        new NotificationTopicPreferenceUpdate(NotificationTopic.COMMUNITY,
                                NotificationChannel.EMAIL, true))))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateMyTopicPreferences_rejectsTheUngovernedChannel() {
        assertThatThrownBy(() -> service().updateMyTopicPreferences(null,
                request(new NotificationTopicPreferenceUpdate(NotificationTopic.COMMUNITY,
                        NotificationChannel.DB, false))))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateMyTopicPreferences_retriesTheLostInsertRaceOnceOnTheWinnersRow() {
        // The L22 race contract: a concurrent PUT inserted the same key
        // first — the loser's INSERT took the unique violation inside the
        // REQUIRES_NEW upsert (absorbed here, OUTSIDE that transaction),
        // and the single retry finds the WINNER's committed row and takes
        // the flip path (value-idempotent).
        callerIsTheUser();
        NotificationTopicPreference winnersRow = NotificationTopicPreference.create(USER,
                NotificationTopic.COMMUNITY, NotificationChannel.EMAIL, true);
        when(repository.findByUserIdAndTopicAndChannel(USER, NotificationTopic.COMMUNITY,
                NotificationChannel.EMAIL))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(repository.save(any(NotificationTopicPreference.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_notification_topic_preferences_once\""));
        when(repository.findByUserId(USER)).thenReturn(List.of());

        service().updateMyTopicPreferences(null,
                request(new NotificationTopicPreferenceUpdate(NotificationTopic.COMMUNITY,
                        NotificationChannel.EMAIL, false)));

        verify(repository, times(1)).save(any());
        assertThat(winnersRow.isEnabled()).isFalse();
    }

    private NotificationTopicPreferencesUpdateRequest request(
            NotificationTopicPreferenceUpdate... updates) {
        return new NotificationTopicPreferencesUpdateRequest(List.of(updates));
    }
}
