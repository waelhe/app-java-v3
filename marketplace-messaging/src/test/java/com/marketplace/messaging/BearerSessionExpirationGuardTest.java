package com.marketplace.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Row 10 (agent findings 2026-10-25, CWE-613): the bearer-session
 * expiration guard — a bearer-authenticated socket is closed at its
 * token's {@code exp}, and the tracking decorator feeds the guard the
 * live sessions. Deterministic throughout: the scheduler is a mock that
 * captures the close task and its instant — running the task by hand is
 * the "expiry arrived" moment.
 */
@ExtendWith(MockitoExtension.class)
class BearerSessionExpirationGuardTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ObjectProvider<TaskScheduler> taskSchedulerProvider;

    @Mock
    private WebSocketSession session;

    @Mock
    private ScheduledFuture<?> pendingClose;

    private BearerSessionExpirationGuard guard;

    @BeforeEach
    void setUp() {
        lenient().when(taskSchedulerProvider.getIfAvailable()).thenReturn(taskScheduler);
        lenient().when(session.getId()).thenReturn("ws-1");
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().doReturn(pendingClose).when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        guard = new BearerSessionExpirationGuard(taskSchedulerProvider);
    }

    private static Jwt jwtExpiringAt(Instant expiresAt) {
        return new Jwt("token-value", Instant.now().minusSeconds(60), expiresAt,
                Map.of("alg", "RS256"), Map.of("sub", "subject-1", "roles", List.of("CONSUMER")));
    }

    private static JwtAuthenticationToken tokenOf(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_CONSUMER"));
    }

    @Test
    void aHandshakeBearerSessionIsClosedAtItsTokenExpiry() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(900);
        when(session.getPrincipal()).thenReturn(tokenOf(jwtExpiringAt(expiresAt)));

        guard.sessionOpened(session);

        ArgumentCaptor<Runnable> closeTask = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(closeTask.capture(), eq(expiresAt));

        // The expiry arrived: the socket is closed with the auth-expiry
        // close status, which removes its subscriptions through the
        // framework's documented disconnect handling.
        closeTask.getValue().run();
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(BearerSessionExpirationGuard.TOKEN_EXPIRED.getCode());
        assertThat(status.getValue().getReason()).isEqualTo("Access token expired");
    }

    @Test
    void aConnectLiftedTokenArmsTheCloseForTheBrowserSession() throws Exception {
        // The browser path: no handshake principal at establish time — the
        // guard learns the expiry from the CONNECT-frame token lift.
        when(session.getPrincipal()).thenReturn(null);
        guard.sessionOpened(session);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));

        Instant expiresAt = Instant.now().plusSeconds(900);
        guard.bearerTokenAuthenticated("ws-1", jwtExpiringAt(expiresAt));

        ArgumentCaptor<Runnable> closeTask = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(closeTask.capture(), eq(expiresAt));
        closeTask.getValue().run();
        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void anEarlyDisconnectCancelsThePendingClose() {
        when(session.getPrincipal()).thenReturn(null);
        guard.sessionOpened(session);
        guard.bearerTokenAuthenticated("ws-1", jwtExpiringAt(Instant.now().plusSeconds(900)));

        guard.sessionClosed(session);

        // The socket is gone before the token expired — no close task may
        // fire into a dead session.
        verify(pendingClose).cancel(false);
    }

    @Test
    void aNonTokenPrincipalSessionIsNeverArmed() {
        // The cookie/session flow — session expiry governs it, not this guard.
        when(session.getPrincipal()).thenReturn(new TestingAuthenticationToken("session-user", "password"));

        guard.sessionOpened(session);

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void aTokenWithoutExpiryIsNeverArmed() {
        Jwt noExpiry = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("sub", "subject-1")
                .build();

        guard.sessionOpened(session);
        guard.bearerTokenAuthenticated("ws-1", noExpiry);

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void anAlreadyExpiredTokenIsNotScheduled() {
        // Overdue tokens are not scheduled — the interceptor's inbound
        // re-validation rejects (and closes) them on their next frame,
        // the same contract a resource-server 401 answers with.
        guard.sessionOpened(session);
        guard.bearerTokenAuthenticated("ws-1", jwtExpiringAt(Instant.now().minusSeconds(30)));

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void aCloseOnAnAlreadyClosedSessionIsANoop() throws Exception {
        when(session.getPrincipal()).thenReturn(null);
        guard.sessionOpened(session);
        guard.bearerTokenAuthenticated("ws-1", jwtExpiringAt(Instant.now().plusSeconds(900)));

        ArgumentCaptor<Runnable> closeTask = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(closeTask.capture(), any(Instant.class));

        // The client disconnected on its own right before expiry.
        when(session.isOpen()).thenReturn(false);
        assertThatCode(closeTask.getValue()::run).doesNotThrowAnyException();
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void withoutASchedulerTheGuardStaysInert() throws Exception {
        // The module-slice shape: no TaskScheduler bean — nothing to arm,
        // nothing to break; the interceptor's inbound safeguard remains.
        BearerSessionExpirationGuard sliceGuard =
                new BearerSessionExpirationGuard(providerOf(null));

        assertThatCode(() -> {
            sliceGuard.sessionOpened(session);
            sliceGuard.bearerTokenAuthenticated("ws-1", jwtExpiringAt(Instant.now().plusSeconds(900)));
        }).doesNotThrowAnyException();
    }

    @Test
    void theTrackingDecoratorObservesTheLifecycleAndDelegatesEverything() throws Exception {
        BearerSessionExpirationGuard guardMock = mock(BearerSessionExpirationGuard.class);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        BearerSessionTrackingHandlerDecorator decorator =
                new BearerSessionTrackingHandlerDecorator(delegate, guardMock);

        decorator.afterConnectionEstablished(session);
        verify(guardMock).sessionOpened(session);
        verify(delegate).afterConnectionEstablished(session);

        decorator.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(guardMock).sessionClosed(session);
        verify(delegate).afterConnectionClosed(session, CloseStatus.NORMAL);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
