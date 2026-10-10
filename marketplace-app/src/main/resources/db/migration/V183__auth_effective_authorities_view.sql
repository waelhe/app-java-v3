-- Phase 1 (the unified plan §10) — the effective login-side authority
-- source: the SINGLE official seam the whole chain already reads, widened
-- at the data layer to the D-03 multi-role model.
--
-- THE CHAIN THIS VIEW SERVES (measured in SecurityConfig, all official
-- Spring Security 7.1.1 mechanisms — nothing here is a parallel
-- mechanism):
--   login → JdbcUserDetailsManager.loadUserByUsername →
--     UserDetails#getAuthorities ← setAuthoritiesByUsernameQuery (THIS
--     view — the one string SecurityConfig wires) →
--   the AS token customizer mints the roles claim from those authorities
--     (SecurityConfig#jwtTokenCustomizer — unchanged) →
--   the resource server maps the claim with JwtGrantedAuthoritiesConverter
--     (unchanged).
-- Active role assignments therefore reach EVERY authority consumer the
-- platform has, through the one store the chain already trusts.
--
-- WHY A VIEW AND NOT A QUERY REWRITE OR A SECOND WRITE PATH:
--  * the manager's authorities mapping declares exactly ONE SQL parameter
--    (JdbcUserDetailsManager's AuthoritiesByUsernameMapping — one
--    declareParameter(username)), so the union cannot be written inline
--    as a two-placeholder query;
--  * the house's one-home rule (FollowedSourcesPort's letter: "Two live
--    queries, one honest union — never a dual write"): auth_authorities
--    keeps its S2/N4/N6 write path (the primary role's projection),
--    user_role_assignments keeps its own (V182), and the read unions
--    them — no materialized copy that two writers must keep in step.
--
-- THE UNION IS THE OLD BEHAVIOR PLUS EXACTLY THE GRANTED ASSIGNMENTS:
--   * every account with no assignment rows (the backfill gave each live
--     account exactly ONE, mirroring its own users.role) reads exactly
--     what it read before — the auth_authorities rows;
--   * a backfilled assignment mirrors users.role, and the maintained
--     state has auth_authorities == users.role (the S2/N4/N6
--     choreography), so UNION collapses the duplicate and the authority
--     set is IDENTICAL to the pre-migration one — the Phase-1 gate
--     «الحسابات القائمة تحفظ وصولها بلا توسيع صلاحيات» holds byte for
--     byte on the maintained state. On a DRIFTED pair (the pre-fix stock:
--     users.role CONSUMER, authority ROLE_ADMIN) the union adds only the
--     account's own recorded users.role authority — the baseline role
--     every account is born with (registration grants roles("CONSUMER"))
--     — and removes nothing;
--   * the break-glass admin is provably unaffected: its authority set
--     stays exactly {ROLE_ADMIN} whether or not a users row (and thus a
--     backfilled ADMIN assignment) exists — UNION deduplicates the
--     identical authority row, which keeps
--     AdminUserInitializer#matchesDerivedDefinition's exact-set contract
--     true;
--   * a GRANT beyond the primary role appears here the moment its row is
--     active; a REVOKE (revoked_at set, the service's own transition)
--     removes the authority at the very next login/token mint — in-flight
--     access tokens die by their documented 900s TTL, live sessions are
--     expired by the UserRoleAssignmentRevoked consumer (the same R8
--     asymmetry AccountStatusChanged rides: grant is fail-closed for live
--     sessions — the new authority is simply absent — so only the REVOKE
--     leg has a named consumer), and refresh resurrection is killed by
--     the service's authorization-row deletion (the L23 documented basis,
--     the identical store UserService.updateUserRole cleans).
--
-- The users JOIN is the honest relation (no denormalized subject): a
-- pseudonymized account's subject is rewritten in place (I7), so the
-- union follows the row automatically; u.is_deleted = FALSE keeps a
-- withdrawn membership from carrying any authority even if an
-- assignment row outlived it.
--
-- View (not table): no storage, no stats to stale, the predicate is
-- re-evaluated per read — the assignments' truth is the assignments'
-- rows, never a copy.

CREATE VIEW auth_effective_authorities AS
    SELECT username, authority
    FROM auth_authorities
    UNION
    SELECT u.subject AS username, 'ROLE_' || ra.role AS authority
    FROM user_role_assignments ra
    JOIN users u ON u.id = ra.user_id
    WHERE ra.revoked_at IS NULL
      AND ra.is_deleted = FALSE
      AND u.is_deleted = FALSE;
