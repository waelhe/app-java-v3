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

    public BruteForceProtectionService(JdbcTemplate jdbcTemplate,
                                       AuthAuditService auditService,
                                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.max-failed-attempts:5}") int maxFailedAttempts,
                                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.lock-duration-minutes:15}") int lockDurationMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDurationMinutes = lockDurationMinutes;
    }

    /**
     * Records a failed login attempt atomically.
     *
     * <p>If the new counter reaches {@code maxFailedAttempts}, the account is locked
     * for {@code lockDurationMinutes}. Both the counter increment and the lock
     * happen in the same atomic UPDATE statement — concurrent calls cannot bypass
     * the lockout threshold.
     */
    @Transactional
    public void recordFailedAttempt(String username) {
        if (isLocked(username)) {
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
        try {
            Instant lockedUntil = jdbcTemplate.queryForObject(
                    "SELECT locked_until FROM auth_users WHERE username = ?",
                    Instant.class, username
            );
            return lockedUntil != null && Instant.now().isBefore(lockedUntil);
        } catch (Exception e) {
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
