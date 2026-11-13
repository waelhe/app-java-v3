package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.EmailVerificationRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-04 unit guards — the email-verification state model (A.2): the LATEST
 * EMAIL_VERIFICATION token row IS the account's verification state (no row =
 * grandfathered; live = pending; consumed = finished — owner-completed or
 * administratively invalidated), the resend guard that re-arms ONLY a
 * pending account, and the redemption leg's hold-lifting write shape.
 * row 8 (agent findings 2026-10-25, CWE-367): both anonymous surfaces take
 * the account's row lock BEFORE their state decisions — the eligibility
 * read on resend, the consumption stamp on redemption — so no
 * administrative disable can interleave with them.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String EMAIL = "member@example.com";
    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuthActionTokenService authActionTokenService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(userRepository, userDetailsManager, eventPublisher,
                authActionTokenService, jdbcTemplate,
                new IdentityMailProperties("http://localhost:3000", Duration.ofMinutes(30), TTL,
                        Duration.ofSeconds(60)));
    }

    private User member() {
        return User.create(EMAIL, EMAIL, "Member", UserRole.CONSUMER);
    }

    private AuthActionToken verificationToken(boolean consumed) {
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, "e".repeat(64), NOW.plus(TTL));
        if (consumed) {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    token, "consumedAt", NOW.minus(Duration.ofMinutes(1)));
        }
        return token;
    }

    @Test
    void issueFor_aKnownSubjectPublishesTheMailEventWithTheDeepLink() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(authActionTokenService.issue(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "raw-verification-token", NOW.plus(TTL))));

        boolean published = service.issueFor(EMAIL);

        assertThat(published).isTrue();
        ArgumentCaptor<EmailVerificationRequestedEvent> event =
                ArgumentCaptor.forClass(EmailVerificationRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(user.getId());
        assertThat(event.getValue().verificationLink())
                .isEqualTo("http://localhost:3000/verify-email?token=raw-verification-token");
        assertThat(event.getValue().expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void issueFor_aSubjectWithoutADomainRowAnswersFalseAndPublishesNothing() {
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.empty());

        assertThat(service.issueFor(EMAIL)).isFalse();
        verifyNoInteractions(authActionTokenService, eventPublisher);
    }

    @Test
    void resend_aPendingAccountIsReArmed() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(member()));
        when(authActionTokenService.issue(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "fresh-token", NOW.plus(TTL))));

        service.resend(EMAIL);

        // A live (unconsumed — expired or not) latest row means PENDING: the
        // resend mints the fresh right and publishes the mail event.
        verify(eventPublisher).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

    /**
     * The 2026-11-09 #807 round (Minor · Functional Correctness): when BOTH
     * case-form rows exist (the case-sensitive primary key admits the
     * legacy mixed-case row AND the normalized one), the normalized-first
     * resolution used to strand the PENDING legacy account forever — the
     * normalized (verified) row won the resolution and answered the silent
     * skip. The resolution now prefers the PENDING account: the legacy row
     * when only it is the pending one.
     */
    @Test
    void resend_thePendingLegacyRowWinsWhenBothCaseFormsExist() {
        String legacy = "Member@Example.com";
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);   // the normalized row
        when(userDetailsManager.userExists(legacy)).thenReturn(true);  // the legacy row
        // The normalized row FINISHED (consumed latest) — the legacy row
        // is the PENDING one. The round-2 subject-first resolution checks
        // the legacy row's pending state FIRST and resolves it without
        // ever consulting the normalized twin's token — the stub stays
        // lenient (it documents the scenario's BOTH-rows state, the
        // resolution order decides which is read).
        org.mockito.Mockito.lenient().when(
                authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(true)));
        when(authActionTokenService.latest(legacy, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(userRepository.findBySubject(legacy))
                .thenReturn(Optional.of(User.create(legacy, legacy, "Member", UserRole.CONSUMER)));
        when(authActionTokenService.issue(legacy, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "legacy-fresh-token", NOW.plus(TTL))));

        service.resend(legacy);

        // The PENDING legacy account is re-armed — the fresh right and the
        // mail event ride the legacy subject, never the verified
        // normalized one.
        verify(authActionTokenService).issue(legacy, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL);
        verify(eventPublisher).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

    /**
     * The 2026-11-09 #807 round-2 review (Minor · Functional
     * Correctness): when BOTH case-form rows are PENDING, the round-5
     * normalized-first order armed the NORMALIZED row while the
     * requester's own mixed-case link armed the address they named —
     * the requested account stayed disabled forever (the round's own
     * stranded-account defect, mirrored). Among pending rows the
     * MATCHING subject now wins.
     */
    @Test
    void resend_theMatchingSubjectWinsWhenBothCaseFormsArePending() {
        String legacy = "Member@Example.com";
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);   // the normalized row
        when(userDetailsManager.userExists(legacy)).thenReturn(true);  // the legacy row
        // BOTH rows are PENDING (live latest rows). The subject-first
        // resolution consults the MATCHING row alone — the normalized
        // twin's stub documents the both-pending state without being
        // read (lenient — the resolution order decides).
        org.mockito.Mockito.lenient().when(
                authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(authActionTokenService.latest(legacy, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(userRepository.findBySubject(legacy))
                .thenReturn(Optional.of(User.create(legacy, legacy, "Member", UserRole.CONSUMER)));
        when(authActionTokenService.issue(legacy, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "legacy-fresh-token", NOW.plus(TTL))));

        service.resend(legacy);

        // The MATCHING (legacy/subject) account is re-armed — the fresh
        // right rides the exact row the requester named, never the
        // normalized twin (which stays pending for its own resend).
        verify(authActionTokenService).issue(legacy, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL);
        verify(authActionTokenService, never()).issue(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL);
        verify(eventPublisher).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

    @Test
    void resend_aConsumedLatestRowIsSkipped_theBanAndFinishedStatesRefuse() {
        // The invariant's own guard: a consumed latest row means finished —
        // the owner completed verification, or an administrative surface
        // consumed the outstanding right. Either way, no fresh redemption
        // right is minted through this surface.
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(true)));

        service.resend(EMAIL);

        verify(authActionTokenService, never()).issue(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

    @Test
    void resend_noTokenRowAtAllMeansGrandfathered_nothingToResend() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty());

        service.resend(EMAIL);

        verify(authActionTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void resend_anUnknownAddressIsASilentNoOp() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(false);

        service.resend(EMAIL);

        verifyNoInteractions(authActionTokenService, eventPublisher, jdbcTemplate);
    }

    @Test
    void resend_serializesOnTheAccountRowBeforeTheEligibilityRead() {
        // row 8 (CWE-367): the lock precedes the pending read — the decision
        // and the issuance both happen under the account's serialization
        // point, so a concurrent administrative disable can never commit
        // between the eligibility check and the token INSERT
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(true)));

        service.resend(EMAIL);

        ArgumentCaptor<String> lockSql = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(jdbcTemplate, authActionTokenService);
        order.verify(jdbcTemplate).queryForList(lockSql.capture(), eq(String.class), eq(EMAIL));
        order.verify(authActionTokenService).latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION);
        assertThat(lockSql.getValue())
                .contains("auth_users")
                .contains("for update");
    }

    @Test
    void completeVerification_liftsTheHoldAndReplaysEveryOtherStoredFlag() {
        AuthActionToken token = verificationToken(false);
        when(authActionTokenService.accountOf("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(EMAIL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{bcrypt}$2a$stored-verifier").authorities("ROLE_CONSUMER")
                        .disabled(true).build());

        service.completeVerification("raw-token");

        ArgumentCaptor<UserDetails> updated = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(updated.capture());
        // The hold lifts: enabled=true; the encoded password is replayed
        // VERBATIM (the verification flow never touches the secret); the
        // authority survives.
        assertThat(updated.getValue().isEnabled()).isTrue();
        assertThat(updated.getValue().getPassword()).isEqualTo("{bcrypt}$2a$stored-verifier");
        assertThat(updated.getValue().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_CONSUMER"));
    }

    @Test
    void completeVerification_locksTheAccountRowBeforeTheConsumptionStamp() {
        // row 8 (CWE-367): the lock order is account pre-read → row lock →
        // consumption stamp → hold-lifting write. A disable that committed
        // before the lock consumed the token (the wall below answers 400);
        // a disable that tries during the redemption WAITS on this lock and
        // applies its ban on top — the honest last-writer order. No
        // interleaving lets the redemption lift a committed ban.
        when(authActionTokenService.accountOf("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(EMAIL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(verificationToken(false));
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{bcrypt}$2a$stored-verifier").authorities("ROLE_CONSUMER")
                        .disabled(true).build());

        service.completeVerification("raw-token");

        InOrder order = inOrder(authActionTokenService, jdbcTemplate, userDetailsManager);
        order.verify(authActionTokenService).accountOf("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION);
        order.verify(jdbcTemplate).queryForList(anyString(), eq(String.class), eq(EMAIL));
        order.verify(authActionTokenService).consume("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION);
        order.verify(userDetailsManager).updateUser(any(UserDetails.class));
    }

    @Test
    void completeVerification_anUnknownTokenAnswersTheHonest400BeforeAnyAccountTouch() {
        // row 8: the pre-read routes an unknown token straight to the same
        // unknown-token 400 the single-use wall itself would answer — no
        // lock is taken, nothing is consumed, no account is touched
        when(authActionTokenService.accountOf("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeVerification("raw-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("link is invalid or has already been used");

        verify(authActionTokenService, never()).consume(any(), any());
        verify(userDetailsManager, never()).updateUser(any(UserDetails.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void completeVerification_whenTheBanConsumedTheTokenTheHoldLiftNeverHappens() {
        // row 8 — the finding's own end state: the administrative disable
        // committed FIRST (its invalidateOutstanding consumed the
        // outstanding right). The redemption's consume — running under the
        // account row lock, after the disable's commit — answers the honest
        // 400, and the hold-lifting write is NEVER reached: the ban cannot
        // be lifted through this surface
        when(authActionTokenService.accountOf("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(EMAIL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenThrow(new BadRequestException(
                        "This email verification link has already been used"));

        assertThatThrownBy(() -> service.completeVerification("raw-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been used");

        verify(userDetailsManager, never()).updateUser(any(UserDetails.class));
    }
    /**
     * The 2026-11-08 #810 round-5 review (Minor · Functional Correctness):
     * the resend resolves the SAME normalized subject registration stores —
     * a mixed-case address used to answer the silent no-op (a 202 with no
     * mail), leaving the account unverified.
     */
    @Test
    void resend_aMixedCaseAddressResolvesTheNormalizedSubject() {
        // The normalized form of the TEST INPUT ("  Mixed@Example.COM " →
        // "mixed@example.com") — not the fixture's own address.
        when(userDetailsManager.userExists("mixed@example.com")).thenReturn(true);
        when(authActionTokenService.latest("mixed@example.com",
                AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(userRepository.findBySubject("mixed@example.com"))
                .thenReturn(Optional.of(member()));
        when(authActionTokenService.issue("mixed@example.com",
                AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "fresh-token", NOW.plus(TTL))));

        service.resend("  Mixed@Example.COM ");

        verify(eventPublisher).publishEvent(any(EmailVerificationRequestedEvent.class));
        verify(authActionTokenService).latest("mixed@example.com",
                AuthActionTokenPurpose.EMAIL_VERIFICATION);
    }

    @Test
    void resend_aLegacyMixedCaseRowStillMatchesTheVerbatimForm() {
        // The REST registration path historically stored mixed-case
        // subjects — the verbatim trimmed form stays the fallback so those
        // rows keep matching.
        when(userDetailsManager.userExists("mixed@example.com")).thenReturn(false);
        when(userDetailsManager.userExists("Mixed@Example.COM")).thenReturn(true);
        when(authActionTokenService.latest("Mixed@Example.COM",
                AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(userRepository.findBySubject("Mixed@Example.COM")).thenReturn(Optional.of(member()));
        when(authActionTokenService.issue("Mixed@Example.COM",
                AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "fresh-token", NOW.plus(TTL))));

        service.resend("  Mixed@Example.COM ");

        verify(eventPublisher).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

}
