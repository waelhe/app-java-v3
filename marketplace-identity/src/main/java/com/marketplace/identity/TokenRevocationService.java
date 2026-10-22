package com.marketplace.identity;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * {@code tokensDeactivateAll} owning use case — the caller's own
 * "sign out everywhere": every issued authorization row (each refresh
 * token and the state it guards) dies for the authenticated principal in
 * one transaction, the same store OAuth2 token revocation operates on
 * (the L23-documented SAS basis every secret-rotating surface uses). The
 * already-minted access JWTs are self-contained and die by their TTL —
 * the platform's standing per-request revocation posture, unchanged.
 */
@Service
@Transactional
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String DELETE_AUTHORIZATIONS_BY_PRINCIPAL =
            "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    private final JdbcTemplate jdbcTemplate;

    public TokenRevocationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Kills every issued authorization for the caller's own principal. */
    @Observed(name = "account.tokens.deactivate_all")
    public int deactivateAllFor(Authentication caller) {
        int killed = jdbcTemplate.update(DELETE_AUTHORIZATIONS_BY_PRINCIPAL, caller.getName());
        log.info("All tokens deactivated for principal: {}, authorizations removed: {}",
                caller.getName(), killed);
        return killed;
    }
}
