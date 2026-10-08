package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-04 unit guards — the auth action token lifecycle's walls, each pinned to
 * its measured authority (the OWASP Forgot Password Cheat Sheet lines the
 * service's javadoc quotes): the random single-use token, the hash-at-rest
 * store, the re-request throttle, and the redemption failures' honest 400s.
 *
 * <p>The fixed clock (the service's own constructor seam) makes every
 * window — expiry, the 60-second throttle floor — deterministic: no
 * sleeping, no flaky margins. {@code createdAt} is container-managed
 * (BaseEntity's auditing listener), so the throttle guard simulates the
 * persisted issuance instant through {@code ReflectionTestUtils} — the
 * house precedent (NeighborhoodPostServiceTest).</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthActionTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String USERNAME = "member@example.com";

    @Mock
    private AuthActionTokenRepository repository;

    private AuthActionTokenService service;

    @BeforeEach
    void setUp() {
        // The configurable OWASP floor at its DEFAULT cadence — the throttle
        // guard below proves the wall at 60s exactly as production runs it.
        service = new AuthActionTokenService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                new IdentityMailProperties("http://localhost:3000", TTL, Duration.ofHours(24),
                        Duration.ofSeconds(60)));
    }

    /** A live token issued at the given instant (createdAt simulated — the auditing listener's write). */
    private AuthActionToken liveTokenIssuedAt(Instant issuedAt) {
        AuthActionToken token = AuthActionToken.issue(
                USERNAME, AuthActionTokenPurpose.PASSWORD_RESET, "a".repeat(64), issuedAt.plus(TTL));
        ReflectionTestUtils.setField(token, "createdAt", issuedAt);
        return token;
    }

    @Test
    void issue_mintsARandomTokenStoredOnlyAsItsSha256HexDigest() {
        Optional<AuthActionTokenService.IssuedToken> issued =
                service.issue(USERNAME, AuthActionTokenPurpose.PASSWORD_RESET, TTL);

        assertThat(issued).as("a fresh account's first issuance always mints").isPresent();
        ArgumentCaptor<AuthActionToken> saved = ArgumentCaptor.forClass(AuthActionToken.class);
        verify(repository).save(saved.capture());
        // Hash at rest (OWASP): the stored value is the 64-char hex digest of
        // the raw token — never the redeemable secret itself.
        assertThat(saved.getValue().getTokenHash()).hasSize(64).matches("[0-9a-f]+");
        assertThat(saved.getValue().getTokenHash())
                .as("the stored digest is NOT the raw token")
                .isNotEqualTo(issued.get().rawToken());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void issue_replacesAnOldOutstandingLiveToken() {
        AuthActionToken outstanding = liveTokenIssuedAt(NOW.minus(Duration.ofMinutes(10)));
        when(repository.findFirstByUsernameAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                USERNAME, AuthActionTokenPurpose.PASSWORD_RESET)).thenReturn(Optional.of(outstanding));

        Optional<AuthActionTokenService.IssuedToken> issued =
                service.issue(USERNAME, AuthActionTokenPurpose.PASSWORD_RESET, TTL);

        assertThat(issued).isPresent();
        // The old right is CONSUMED in the same transaction — the newest mail
        // always carries the only redeemable token (the V112 single-flight).
        assertThat(outstanding.getConsumedAt()).isEqualTo(NOW);
        verify(repository).save(any(AuthActionToken.class));
    }

    @Test
    void issue_insideTheThrottleWindowIsASilentNoOp() {
        // OWASP's per-account flood wall: a live token issued 30 seconds ago
        // (inside the 60-second floor) is neither replaced nor re-mailed.
        AuthActionToken recent = liveTokenIssuedAt(NOW.minus(Duration.ofSeconds(30)));
        when(repository.findFirstByUsernameAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                USERNAME, AuthActionTokenPurpose.PASSWORD_RESET)).thenReturn(Optional.of(recent));

        Optional<AuthActionTokenService.IssuedToken> issued =
                service.issue(USERNAME, AuthActionTokenPurpose.PASSWORD_RESET, TTL);

        assertThat(issued).as("the throttled re-issue answers empty").isEmpty();
        assertThat(recent.getConsumedAt()).as("the outstanding token stays live").isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void consume_aValidTokenIsMarkedConsumedAndReturned() {
        AuthActionToken token = liveTokenIssuedAt(NOW.minus(Duration.ofMinutes(1)));
        when(repository.findByTokenHashAndPurpose(anyString(), eq(AuthActionTokenPurpose.PASSWORD_RESET)))
                .thenReturn(Optional.of(token));

        AuthActionToken redeemed =
                service.consume("the-raw-token", AuthActionTokenPurpose.PASSWORD_RESET);

        assertThat(redeemed).isSameAs(token);
        assertThat(token.getConsumedAt()).as("single use: the redemption stamps consumption")
                .isEqualTo(NOW);
        // CodeRabbit #4209499485 adoption: the redemption flushes INSIDE the
        // method (saveAndFlush) so the optimistic-lock version check surfaces
        // in the documented catch — the test follows the strengthened
        // persistence contract.
        verify(repository).saveAndFlush(token);
    }

    @Test
    void consume_anUnknownTokenAnswersTheHonest400() {
        when(repository.findByTokenHashAndPurpose(anyString(), eq(AuthActionTokenPurpose.PASSWORD_RESET)))
                .thenReturn(Optional.empty());

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.consume("unknown", AuthActionTokenPurpose.PASSWORD_RESET));
        assertThat(ex.getMessage()).contains("invalid");
    }

    @Test
    void consume_anAlreadyUsedTokenAnswers400() {
        AuthActionToken consumed = liveTokenIssuedAt(NOW.minus(Duration.ofMinutes(1)));
        ReflectionTestUtils.setField(consumed, "consumedAt", NOW.minus(Duration.ofMinutes(1)));
        when(repository.findByTokenHashAndPurpose(anyString(), eq(AuthActionTokenPurpose.PASSWORD_RESET)))
                .thenReturn(Optional.of(consumed));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.consume("spent", AuthActionTokenPurpose.PASSWORD_RESET));
        assertThat(ex.getMessage()).contains("already been used");
    }

    @Test
    void consume_anExpiredTokenAnswers400() {
        // Issued 31 minutes ago under the 30-minute TTL — the right is dead.
        AuthActionToken expired = liveTokenIssuedAt(NOW.minus(Duration.ofMinutes(31)));
        when(repository.findByTokenHashAndPurpose(anyString(), eq(AuthActionTokenPurpose.PASSWORD_RESET)))
                .thenReturn(Optional.of(expired));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.consume("stale", AuthActionTokenPurpose.PASSWORD_RESET));
        assertThat(ex.getMessage()).contains("expired");
    }

    @Test
    void invalidateOutstanding_consumesEveryLiveRowForTheAccount() {
        AuthActionToken reset = liveTokenIssuedAt(NOW.minus(Duration.ofMinutes(2)));
        AuthActionToken verification = AuthActionToken.issue(
                USERNAME, AuthActionTokenPurpose.EMAIL_VERIFICATION, "b".repeat(64), NOW.plus(Duration.ofHours(24)));
        when(repository.findOutstanding(USERNAME)).thenReturn(List.of(reset, verification));

        service.invalidateOutstanding(USERNAME);

        // The ban-vs-verification invariant: a disabled/pseudonymized account
        // holds NO outstanding redemption right — any purpose.
        assertThat(reset.getConsumedAt()).isEqualTo(NOW);
        assertThat(verification.getConsumedAt()).isEqualTo(NOW);
    }
}
