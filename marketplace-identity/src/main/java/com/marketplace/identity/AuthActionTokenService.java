package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * A-04 (official-compliance plan §6, wave A — A.1/A.2): the auth action
 * token lifecycle — issue, redeem, invalidate — shared by the
 * password-reset and email-verification journeys. The MECHANICS here are
 * deliberately identical for both purposes (one mechanism, one store, one
 * set of walls) because that is what the plan's own pairing of A.1 and A.2
 * into one unit presumes; only the caller's business leg differs.
 *
 * <p><b>The measured authorities for every wall in this class:</b></p>
 * <ul>
 *   <li><b>Randomness</b> — OWASP Forgot Password Cheat Sheet (the declared
 *       trusted community source, plan §5.3): tokens "Randomly generated
 *       using a cryptographically safe algorithm" — {@link SecureRandom},
 *       256 bits, Base64URL.</li>
 *   <li><b>Single use</b> — same sheet: "Single use and expire after an
 *       appropriate period". Enforced twice: the V112 live-token partial
 *       unique index (one live row per account+purpose at the store level)
 *       and the inherited {@code @Version} optimistic lock here — two
 *       concurrent redemptions of the same token cannot both commit; the
 *       loser surfaces as the honest 400 below, never a double spend.</li>
 *   <li><b>Hash at rest</b> — same sheet's storage guidance: only the
 *       SHA-256 digest is stored ({@code token_hash}, V112); the raw value
 *       exists in the mail and the transient event payload only.</li>
 *   <li><b>Re-request throttling</b> — same sheet: "Implement protections
 *       against excessive automated submissions such as rate-limiting on a
 *       per-account basis... Otherwise an attacker could make thousands of
 *       password reset requests per hour for a given account, flooding the
 *       user's intake system". The per-account guard here is the
 *       data-level one the store can already honor: a live token issued
 *       within {@link #MIN_ISSUE_INTERVAL} is NOT reissued and NO second
 *       mail is sent — the request still answers the same success the
 *       enumeration-safe contract mandates, silently. (The cross-account
 *       rate limiter is B-05's Bucket4j unit on the other track — the
 *       foundation BOM already landed its version.)</li>
 * </ul>
 *
 * <p><b>Transactionality:</b> every method joins the CALLER's transaction
 * ({@link Propagation#REQUIRED} — the default, stated because the atomicity
 * is the point): the token row and the event publication entry commit
 * together on issue, and the redemption's business writes commit together
 * with the consumption stamp.</p>
 */
@Service
@Transactional
public class AuthActionTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthActionTokenService.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthActionTokenRepository repository;
    private final Clock clock;
    private final IdentityMailProperties properties;

    /**
     * The house {@code ClockConfig} UTC bean — a constructor seam, so the
     * unit guards drive issuance/expiry/throttle windows with a fixed clock
     * ({@code Clock.fixed}) instead of sleeping: deterministic walls. The
     * re-issue floor comes from {@link IdentityMailProperties#minIssueInterval()}
     * (default 60s — the OWASP flood wall: one mail per account per minute
     * while a genuine "it didn't arrive" second click still re-issues).
     */
    public AuthActionTokenService(AuthActionTokenRepository repository, Clock clock,
                                  IdentityMailProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    /** The issue result: the raw one-time secret (never persisted) and its expiry. */
    public record IssuedToken(String rawToken, Instant expiresAt) {}

    /**
     * Issues (or silently declines to reissue) a live token for the
     * account+purpose. The replacement rule: an outstanding live token for
     * the same pair is CONSUMED first, then the new row inserts — the V112
     * live-token unique index guarantees at most one redeemable token, and
     * the newest mail always carries the only live one.
     *
     * @return the issued token, or empty when the throttle window is active
     * (the caller answers its enumeration-safe success and sends nothing)
     */
    public Optional<IssuedToken> issue(String username, AuthActionTokenPurpose purpose,
                                       Duration ttl) {
        Instant now = clock.instant();
        Optional<AuthActionToken> live = repository
                .findFirstByUsernameAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(username, purpose);
        if (live.isPresent()) {
            AuthActionToken outstanding = live.get();
            if (outstanding.getCreatedAt() != null
                    && outstanding.getCreatedAt().isAfter(now.minus(properties.minIssueInterval()))) {
                log.info("Auth action token re-issue throttled: username={}, purpose={}",
                        username, purpose);
                return Optional.empty();
            }
            outstanding.consume(now);
            // CodeRabbit #4209499475 (adopted from the root): flush the
            // consumption BEFORE the replacement INSERTs. Hibernate does not
            // guarantee UPDATE-before-INSERT ordering at flush; if the INSERT
            // ran first, the V112 partial unique index ix_auth_action_tokens_live
            // would still see the old row as live and reject the new one — a
            // re-request would surface as a 500. saveAndFlush pins the order.
            repository.saveAndFlush(outstanding);
        }
        byte[] secret = new byte[32];
        SECURE_RANDOM.nextBytes(secret);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(ttl);
        repository.save(AuthActionToken.issue(username, purpose, sha256Hex(rawToken), expiresAt));
        return Optional.of(new IssuedToken(rawToken, expiresAt));
    }

    /**
     * Redeems a presented raw token for its purpose — the single-use wall.
     * Every failure is the same honest 400 ({@link BadRequestException} —
     * the A-03 error contract), with a distinct detail the client can
     * render: unknown token, already redeemed, or expired. The detail never
     * distinguishes WHETHER the underlying account exists beyond what the
     * token itself proves — the token IS the proof.
     *
     * @return the consumed row (the caller reads the account it redeems for)
     */
    public AuthActionToken consume(String rawToken, AuthActionTokenPurpose purpose) {
        Instant now = clock.instant();
        AuthActionToken token = repository.findByTokenHashAndPurpose(sha256Hex(rawToken), purpose)
                .orElseThrow(() -> new BadRequestException(
                        "This " + purpose.name().toLowerCase().replace('_', ' ')
                                + " link is invalid or has already been used"));
        if (token.isConsumed()) {
            throw new BadRequestException(
                    "This " + purpose.name().toLowerCase().replace('_', ' ')
                            + " link has already been used");
        }
        if (token.isExpired(now)) {
            throw new BadRequestException(
                    "This " + purpose.name().toLowerCase().replace('_', ' ')
                            + " link has expired — request a new one");
        }
        token.consume(now);
        try {
            // CodeRabbit #4209499485 (adopted from the root): flush INSIDE the
            // try block so the optimistic-lock version check surfaces here,
            // not at commit after the method has returned. A plain save() on a
            // managed entity defers the UPDATE — the catch below was dead code
            // and the second concurrent redemption would have answered a 500
            // instead of the documented 400.
            repository.saveAndFlush(token);
        } catch (OptimisticLockingFailureException ex) {
            // The second of two concurrent redemptions lands here — the
            // version wall answered exactly-once at the persistence layer.
            log.info("Auth action token concurrent redemption rejected: purpose={}", purpose);
            throw new BadRequestException(
                    "This " + purpose.name().toLowerCase().replace('_', ' ')
                            + " link has already been used");
        }
        return token;
    }

    /**
     * Consumes every live token for the account — the administrative
     * surfaces' leg of the ban-vs-verification invariant: a disabled or
     * pseudonymized account holds NO outstanding redemption right, so the
     * verification surface can never re-enable what an administrator shut,
     * and the pseudonymization's {@code deleteUser} never trips the V112
     * FK. The consumption is Envers-audited (the {@code @Audited} entity)
     * — the revision distinguishes an administrative consumption from a
     * user redemption after the fact.
     */
    public void invalidateOutstanding(String username) {
        repository.findOutstanding(username).forEach(token -> token.consume(clock.instant()));
        if (log.isDebugEnabled()) {
            log.debug("Outstanding auth action tokens invalidated: username={}", username);
        }
    }

    /** The latest token row for the account+purpose — the verification state read. */
    public Optional<AuthActionToken> latest(String username, AuthActionTokenPurpose purpose) {
        return repository.findFirstByUsernameAndPurposeOrderByCreatedAtDesc(username, purpose);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JVM-mandated algorithm (JCA documented behavior)
            // — unreachable on any compliant runtime.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
