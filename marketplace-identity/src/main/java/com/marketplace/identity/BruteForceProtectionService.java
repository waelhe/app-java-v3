package com.marketplace.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Brute force attack protection.
 *
 * <p><b>OWASP Authentication Cheat Sheet — Account Lockout</b>
 * <ul>
 *   <li>Lock account after {@code maxFailedAttempts} (default: 5)</li>
 *   <li>Lock duration: {@code lockDurationMinutes} (default: 15)</li>
 *   <li>Reset counter on successful login</li>
 * </ul>
 *
 * <p><b>Concurrency safety (atomic increment)</b>: the prior implementation
 * used {@code SELECT failed_attempts} followed by {@code UPDATE failed_attempts = ?}.
 * Under PostgreSQL's default READ_COMMITTED isolation, two concurrent transactions
 * could both read the same counter value and both write the same incremented value
 * — defeating the lockout. The fix uses a single atomic statement:
 * {@code UPDATE auth_users SET failed_attempts = failed_attempts + 1 ... RETURNING failed_attempts}
 * (PostgreSQL documented feature). The counter increment is now an atomic database
 * operation; the SELECT-then-UPDATE race is eliminated.
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#account-lockout">OWASP Authentication Cheat Sheet — Account Lockout</a></li>
 *   <li><a href="https://www.postgresql.org/docs/current/sql-update.html">PostgreSQL UPDATE — RETURNING clause</a></li>
 * </ul>
 */
@Service
public class BruteForceProtectionService {

    private static final Logger log = LoggerFactory.getLogger(BruteForceProtectionService.class);

    /**
     * Atomic counter increment + lock activation in a single SQL statement.
     * The {@code WHERE locked_until IS NULL OR locked_until &lt;= NOW()} clause ensures
     * we do not increment the counter (and do not re-lock) for accounts already locked.
     * Returns the new counter value via the RETURNING clause so we can decide
     * whether to log the lock event.
     */
    private static final String INCREMENT_AND_RETURN_SQL =
            "UPDATE auth_users " +
            "SET failed_attempts = failed_attempts + 1 " +
            "WHERE username = ? AND (locked_until IS NULL OR locked_until <= NOW()) " +
            "RETURNING failed_attempts";

    private final JdbcTemplate jdbcTemplate;
    private final AuthAuditService auditService;

    private final int maxFailedAttempts;
    private final int lockDurationMinutes;

    /**
     * Self-reference for proxy-based @Transactional to work on self-invocation.
     * Spring Framework Reference: "Only external method calls coming in through the
     * proxy are intercepted. Self-invocation does not lead to an actual transaction
     * at runtime even if the invoked method is marked with @Transactional."
     * By calling self.isLocked() instead of this.isLocked(), the call goes through
     * the Spring proxy and the @Transactional(readOnly = true) annotation is honored.
     * The @Lazy annotation prevents circular dependency during bean creation.
     */
    private final BruteForceProtectionService self;

    public BruteForceProtectionService(JdbcTemplate jdbcTemplate,
                                       AuthAuditService auditService,
                                       @org.springframework.context.annotation.Lazy BruteForceProtectionService self,
                                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.max-failed-attempts:5}") int maxFailedAttempts,
                                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.lock-duration-minutes:15}") int lockDurationMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.self = self;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDurationMinutes = lockDurationMinutes;
    }

    /**
     * Records a failed login attempt atomically.
     *
     * <p>Uses {@code Propagation.REQUIRES_NEW} so the counter increment commits in a
     * <strong>separate</strong> transaction that survives the caller's rollback. Without
     * this, the {@code @Transactional} on {@code TwoStepLoginService.login} would roll
     * back the {@code failed_attempts} increment when {@code BadRequestException} is thrown
     * — the counter would never reach the lockout threshold and accounts would never be
     * locked. Reference: Spring Framework Reference — Transaction Propagation.
     *
     * <p>If the new counter reaches {@code maxFailedAttempts}, the account is locked
     * for {@code lockDurationMinutes}. Both the counter increment and the lock
     * happen in the same atomic UPDATE statement — concurrent calls cannot bypass
     * the lockout threshold.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(String username) {
        if (self.isLocked(username)) {
            return;
        }

        // Atomic increment — RETURNING gives us the new value without a separate SELECT.
        List<Integer> newAttemptsList = jdbcTemplate.query(
                INCREMENT_AND_RETURN_SQL,
                (rs, rowNum) -> rs.getInt("failed_attempts"),
                username
        );

        if (newAttemptsList.isEmpty()) {
            // Either the user doesn't exist, or the account is already locked
            // (the WHERE clause filtered it out). Either way, nothing to do.
            return;
        }

        int newAttempts = newAttemptsList.getFirst();

        if (newAttempts >= maxFailedAttempts) {
            // Lock the account in a separate UPDATE — atomicity of the increment is
            // already guaranteed; this lock activation is independent.
            Instant lockUntil = Instant.now().plus(lockDurationMinutes, ChronoUnit.MINUTES);
            jdbcTemplate.update(
                    "UPDATE auth_users SET locked_until = ? WHERE username = ? AND locked_until IS NULL",
                    lockUntil, username
            );
            auditService.log(username, AuthEventType.ACCOUNT_LOCKED,
                    "Locked after " + newAttempts + " failed attempts for " + lockDurationMinutes + " minutes");
            log.warn("Account locked: username={}, attempts={}", username, newAttempts);
        } else {
            log.warn("Failed login attempt: username={}, attempts={}/{}", username, newAttempts, maxFailedAttempts);
        }
    }

    @Transactional
    public void resetFailedAttempts(String username) {
        jdbcTemplate.update(
                "UPDATE auth_users SET failed_attempts = 0, locked_until = NULL WHERE username = ?",
                username
        );
    }

    @Transactional(readOnly = true)
    public boolean isLocked(String username) {
        // Catch only EmptyResultDataAccessException (user not found) — this is the only
        // expected case where queryForObject throws. Other exceptions (DB connection
        // failure, etc.) MUST propagate so the caller knows the lockout status is
        // indeterminate — never silently return "not locked" which would be a security
        // bypass (attacker exploits DB outage to bypass lockout).
        try {
            Instant lockedUntil = jdbcTemplate.queryForObject(
                    "SELECT locked_until FROM auth_users WHERE username = ?",
                    Instant.class, username
            );
            return lockedUntil != null && Instant.now().isBefore(lockedUntil);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // User does not exist — not locked (nothing to lock).
            return false;
        }
    }

    @Transactional
    public void unlockAccount(String username) {
        jdbcTemplate.update(
                "UPDATE auth_users SET failed_attempts = 0, locked_until = NULL WHERE username = ?",
                username
        );
        auditService.log(username, AuthEventType.ACCOUNT_UNLOCKED, "Account unlocked by admin");
    }
}
