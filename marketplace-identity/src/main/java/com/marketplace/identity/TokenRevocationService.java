package com.marketplace.identity;

import com.marketplace.shared.api.AccountSessionsRevoked;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's "sign out everywhere": kills every issued OAuth authorization
 * for the caller's own principal AND completes the intent on the session
 * side in the same transaction.
 *
 * <p>Row 13 (agent findings 2026-10-26 14:33 export, CWE-613): the
 * pre-fix surface deleted the stored authorization rows alone — a live
 * browser session keeps its authenticated security context and authorizes
 * again immediately, re-creating exactly the replacement grants the
 * revocation just removed, without any credential challenge. The fix is
 * the house R8 channel (the {@code AccountCredentialsRotated} precedent):
 * the revocation fact published in THIS transaction, consumed AFTER_COMMIT
 * by {@code AccountStatusSessionInvalidator} — every session of the
 * principal expires with the commit, and the framework's resubmission
 * covers a failed expiry.</p>
 */
@Service
@Transactional
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String DELETE_AUTHORIZATIONS_BY_PRINCIPAL =
            "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public TokenRevocationService(JdbcTemplate jdbcTemplate,
                                  ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Kills every issued authorization for the caller's own principal and
     * expires every live session of that principal after commit — the two
     * halves of one owner intent.
     */
    @Observed(name = "account.tokens.deactivate_all")
    public int deactivateAllFor(Authentication caller) {
        String principal = caller.getName();
        int killed = jdbcTemplate.update(DELETE_AUTHORIZATIONS_BY_PRINCIPAL, principal);
        // Row 13: the session half of "sign out everywhere" — see the class
        // javadoc. Published inside the transaction so the publication
        // registry entry commits atomically with the authorization delete.
        eventPublisher.publishEvent(new AccountSessionsRevoked(principal));
        log.info("All tokens deactivated for principal: {}, authorizations removed: {}",
                principal, killed);
        return killed;
    }
}
