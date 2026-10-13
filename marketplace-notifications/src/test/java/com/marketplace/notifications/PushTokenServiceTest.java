package com.marketplace.notifications;

import com.marketplace.notifications.push.PushNotificationPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 7 (ADR-0003) — the push routing's unit gate: the preference gate
 * + the token lookup + the provider's per-token verdicts (the dead tokens
 * leave the registry, the live ones answer success), and the register
 * upsert (the UNIQUE token key collapses the repeated calls).
 */
@ExtendWith(MockitoExtension.class)
class PushTokenServiceTest {

    @Mock
    private PushTokenRepository pushTokenRepository;
    @Mock
    private PushNotificationPort pushNotificationPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private PushTokenService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PushTokenService(pushTokenRepository, pushNotificationPort, currentUserProvider);
    }

    @Test
    void thePreferenceGateAnsweringNothingForTheOptedOut() {
        // The routing half is exercised through NotificationService's own
        // gate (its unit gate covers it); here the service's contract:
        // no tokens → no provider call at all.
        when(pushTokenRepository.findByUserId(userId)).thenReturn(List.of());

        boolean sent = service.sendToUser(userId, NotificationType.ORDER_CONFIRMED, "t", "b");

        assertThat(sent).isFalse();
        verify(pushNotificationPort, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    void theProviderVerdictsPruneOnlyTheDeadTokens() {
        PushToken live = PushToken.register(userId, "live-token", PushToken.Platform.ANDROID);
        PushToken dead = PushToken.register(userId, "dead-token", PushToken.Platform.IOS);
        when(pushTokenRepository.findByUserId(userId)).thenReturn(List.of(live, dead));
        when(pushTokenRepository.findByToken("dead-token")).thenReturn(Optional.of(dead));
        when(pushNotificationPort.send(anyList(), anyString(), anyString())).thenReturn(List.of(
                new PushNotificationPort.PushResult("live-token", true, false),
                new PushNotificationPort.PushResult("dead-token", false, true)));

        boolean sent = service.sendToUser(userId, NotificationType.ORDER_CONFIRMED, "t", "b");

        assertThat(sent).isTrue();
        verify(pushTokenRepository).delete(dead);
        verify(pushTokenRepository, never()).delete(live);
    }

    @Test
    void theWholeBatchFailurePrunesNothing() {
        PushToken token = PushToken.register(userId, "t1", PushToken.Platform.WEB);
        when(pushTokenRepository.findByUserId(userId)).thenReturn(List.of(token));
        when(pushNotificationPort.send(anyList(), anyString(), anyString())).thenReturn(List.of(
                new PushNotificationPort.PushResult("t1", false, false)));

        boolean sent = service.sendToUser(userId, NotificationType.ORDER_CONFIRMED, "t", "b");

        assertThat(sent).isFalse();
        verify(pushTokenRepository, never()).delete(any(PushToken.class));
    }

    @Test
    void theRegisterCallIsTheUpsertByToken() {
        PushToken existing = PushToken.register(UUID.randomUUID(), "same-token", PushToken.Platform.WEB);
        when(pushTokenRepository.findByToken("same-token")).thenReturn(Optional.of(existing));
        when(pushTokenRepository.save(any(PushToken.class))).thenAnswer(inv -> inv.getArgument(0));

        PushToken saved = service.register(userId, "same-token", PushToken.Platform.ANDROID);

        // The reinstall path REBINDS — one hardware token, one row.
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getPlatform()).isEqualTo(PushToken.Platform.ANDROID);
        verify(pushTokenRepository).save(existing);
    }

    @Test
    void theUnregisterIsTheOwnersOwn() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        service.unregisterForCaller("my-token", authentication);

        verify(pushTokenRepository).deleteByUserIdAndToken(userId, "my-token");
    }
}
