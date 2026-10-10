package com.marketplace.shared.security;

import com.marketplace.shared.api.AccountStatusChanged;
import com.marketplace.shared.api.UserRoleAssignmentRevoked;
import com.marketplace.shared.api.UserRoleChanged;

import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8 (comprehensive-review-ar fix plan §4, Wave 1) — unit guard for
 * {@link AccountStatusSessionInvalidator}.
 *
 * <p><b>Real framework objects, no mocks on the security path.</b> The
 * invalidator is exercised against a real
 * {@link SpringSessionBackedSessionRegistry} — the exact class
 * {@code SecurityConfig} wires — over a real indexed
 * {@code FindByIndexNameSessionRepository} backed by a map. The fixture
 * mirrors {@code MapSessionRepository}'s documented semantics (defensive copy
 * on save and findById) plus the principal index of
 * {@code RedisIndexedSessionRepository} (sessions indexed by the
 * {@code PRINCIPAL_NAME} attribute — the attribute
 * {@code PrincipalNameIndexResolver} resolves and
 * {@code SpringSessionBackedSessionInformation} reads back), so the
 * production contract — expireNow persists the official expiry marker into
 * the session store — is proven on the real classes, only the store differs
 * (the {@code EdgeSessionIT} precedent's reasoning, applied at unit scale).
 *
 * <p>The expiry assertion is the enforcer's own check:
 * {@code SessionInformation.isExpired()} re-reads the session from the
 * repository on every call — the exact method
 * {@code ConcurrentSessionFilter} invokes per request — so a {@code true}
 * here proves the marker round-tripped through {@code save()} into the
 * store, not an in-memory flag.
 */
class AccountStatusSessionInvalidatorTest {

    private static final String PRINCIPAL = "r8-target@example.com";
    private static final String OTHER_PRINCIPAL = "r8-bystander@example.com";

    private final IndexedMapSessionRepository repository = new IndexedMapSessionRepository();

    private final SpringSessionBackedSessionRegistry<MapSession> registry =
            new SpringSessionBackedSessionRegistry<>(repository);

    private final AccountStatusSessionInvalidator invalidator =
            new AccountStatusSessionInvalidator(registry);

    /**
     * The comprehensive review's exact scenario: the account holds live
     * sessions, the status flips to DISABLED — every session of the principal
     * carries the persisted expiry marker afterwards (the next request
     * through any session-aware chain is rejected and logged out), and the
     * registry — the enforcer's view — no longer lists them as live.
     */
    @Test
    void disableExpiresAllLiveSessionsOfTheAccount() {
        MapSession first = sessionFor(PRINCIPAL);
        MapSession second = sessionFor(PRINCIPAL);
        MapSession bystander = sessionFor(OTHER_PRINCIPAL);

        invalidator.onAccountStatusChanged(new AccountStatusChanged(UUID.randomUUID(), PRINCIPAL, false));

        assertThat(registry.getSessionInformation(first.getId())).as("first session is expired").isNotNull()
                .extracting(info -> info.isExpired()).isEqualTo(true);
        assertThat(registry.getSessionInformation(second.getId())).as("second session is expired").isNotNull()
                .extracting(info -> info.isExpired()).isEqualTo(true);
        assertThat(registry.getAllSessions(PRINCIPAL, false)).as("no live sessions remain").isEmpty();
        assertThat(registry.getSessionInformation(bystander.getId())).isNotNull()
                .as("the bystander's session is untouched").extracting(info -> info.isExpired()).isEqualTo(false);
    }

    /**
     * The negative leg (CodeRabbit round 1 on the fix plan, adopted from the
     * root): activation publishes the event — the complete domain fact — but
     * expiring an enabled account's sessions has zero security value, so the
     * listener leaves them intact.
     */
    @Test
    void enableKeepsSessionsIntact() {
        MapSession session = sessionFor(PRINCIPAL);

        invalidator.onAccountStatusChanged(new AccountStatusChanged(UUID.randomUUID(), PRINCIPAL, true));

        assertThat(registry.getAllSessions(PRINCIPAL, false)).as("enabled account keeps its sessions").hasSize(1);
        assertThat(registry.getSessionInformation(session.getId())).isNotNull()
                .extracting(info -> info.isExpired()).isEqualTo(false);
    }

    /**
     * The role-change leg: the authorities cached inside every live session
     * are stale the moment the projection is replaced, so all of the
     * account's sessions expire and the next authorization request
     * re-authenticates against the new roles claim source.
     */
    @Test
    void roleChangeExpiresAllSessionsOfTheAccount() {
        MapSession session = sessionFor(PRINCIPAL);

        invalidator.onUserRoleChanged(new UserRoleChanged(UUID.randomUUID(), PRINCIPAL, "CONSUMER", "PROVIDER"));

        assertThat(registry.getSessionInformation(session.getId())).isNotNull()
                .as("role change expires the stale-authority session").extracting(info -> info.isExpired())
                .isEqualTo(true);
        assertThat(registry.getAllSessions(PRINCIPAL, false)).isEmpty();
    }

    /**
     * Phase 1 (the unified plan §10, D-03) — the multi-role REVOKE leg: a
     * withdrawn assignment is fail-OPEN inside every live session whose
     * cached authorities still hold the role (the V183 view repairs the
     * source only at the NEXT login/token mint), so all of the account's
     * sessions expire now. The GRANT leg publishes nothing by design — it
     * is fail-closed for live sessions, the documented asymmetry this
     * consumer's {@code AccountStatusChanged} leg already rides.
     */
    @Test
    void roleAssignmentRevocationExpiresAllSessionsOfTheAccount() {
        MapSession first = sessionFor(PRINCIPAL);
        MapSession bystander = sessionFor(OTHER_PRINCIPAL);

        invalidator.onUserRoleAssignmentRevoked(
                new UserRoleAssignmentRevoked(UUID.randomUUID(), PRINCIPAL, "PROVIDER"));

        assertThat(registry.getSessionInformation(first.getId())).isNotNull()
                .as("the revoked role's stale-authority sessions expire")
                .extracting(info -> info.isExpired()).isEqualTo(true);
        assertThat(registry.getAllSessions(PRINCIPAL, false)).isEmpty();
        assertThat(registry.getSessionInformation(bystander.getId())).isNotNull()
                .as("the bystander's session is untouched")
                .extracting(info -> info.isExpired()).isEqualTo(false);
    }

    /**
     * The revoke re-delivered after the sessions have already timed out is
     * a clean no-op — the publication registry's resubmission must never
     * surface as an error (the disable no-op's own reasoning, on the
     * assignment carrier).
     */
    @Test
    void roleAssignmentRevocationWithNoSessionsIsANoOp() {
        invalidator.onUserRoleAssignmentRevoked(
                new UserRoleAssignmentRevoked(UUID.randomUUID(), "nobody@example.com", "ADMIN"));

        assertThat(registry.getAllSessions("nobody@example.com", false)).isEmpty();
    }

    /**
     * An event for an account with no sessions anywhere is a clean no-op —
     * the framework's resubmission may re-deliver after the sessions have
     * already timed out, and that must never surface as an error.
     */
    @Test
    void disableWithNoSessionsIsANoOp() {
        invalidator.onAccountStatusChanged(new AccountStatusChanged(UUID.randomUUID(), "nobody@example.com", false));

        assertThat(registry.getAllSessions("nobody@example.com", false)).isEmpty();
    }

    /**
     * Idempotency of re-delivery: the invalidator queries with
     * {@code getAllSessions(principal, false)} — already-expired sessions are
     * filtered out — so a second delivery of the same disable (the
     * publication registry's resubmission) expires nothing again and the
     * store is unchanged.
     */
    @Test
    void redispatchedDisableIsIdempotent() {
        sessionFor(PRINCIPAL);

        invalidator.onAccountStatusChanged(new AccountStatusChanged(UUID.randomUUID(), PRINCIPAL, false));
        invalidator.onAccountStatusChanged(new AccountStatusChanged(UUID.randomUUID(), PRINCIPAL, false));

        assertThat(registry.getAllSessions(PRINCIPAL, false)).isEmpty();
    }

    private MapSession sessionFor(String principal) {
        MapSession session = new MapSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal);
        repository.save(session);
        return session;
    }

    /**
     * A real {@link FindByIndexNameSessionRepository} over a map — the
     * attribute-indexed contract {@code SpringSessionBackedSessionRegistry}
     * depends on: {@code MapSessionRepository}'s copy semantics plus the
     * principal index {@code RedisIndexedSessionRepository} maintains in
     * production.
     */
    private static final class IndexedMapSessionRepository
            implements FindByIndexNameSessionRepository<MapSession> {

        private final Map<String, MapSession> sessions = new HashMap<>();

        @Override
        public MapSession createSession() {
            return new MapSession();
        }

        @Override
        public Map<String, MapSession> findByIndexNameAndIndexValue(String indexName, String indexValue) {
            Map<String, MapSession> matches = new HashMap<>();
            if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
                return matches;
            }
            for (MapSession session : sessions.values()) {
                Object principal = session.getAttribute(PRINCIPAL_NAME_INDEX_NAME);
                if (principal != null && indexValue.equals(principal.toString())) {
                    matches.put(session.getId(), session);
                }
            }
            return matches;
        }

        @Override
        public MapSession findById(String id) {
            MapSession stored = sessions.get(id);
            return stored == null ? null : new MapSession(stored);
        }

        @Override
        public void save(MapSession session) {
            sessions.put(session.getId(), new MapSession(session));
        }

        @Override
        public void deleteById(String id) {
            sessions.remove(id);
        }
    }
}
