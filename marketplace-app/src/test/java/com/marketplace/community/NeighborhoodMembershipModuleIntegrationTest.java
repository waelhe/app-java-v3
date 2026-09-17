package com.marketplace.community;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L41 (neighborhood community plan §5 — the membership anchor) — the
 * acceptance criteria over the REAL chain: HTTP → the resource-server
 * chain → the /me surface → the REAL geo service (the fixed seed tree
 * answers the existence and level gates — no port mock: the level-3
 * contract is the real service's own) → V60's real schema (the CHECK,
 * the partial unique index, the Envers mirror) → the b-2/b-3 export and
 * purge seams.
 *
 * <p>Every test uses its OWN random user id (no test-level transaction —
 * each service call commits its own, the SavedSearchIntegrationTest
 * convention), so the shared container's state never bleeds between
 * tests.
 *
 * <p>Acceptance criteria: (1) join a level-3 node ⇒ 201 and the read
 * returns it; (2) a level 0-2 node ⇒ 400 and an unknown id ⇒ 404 — both
 * BEFORE any write; (3) a second active membership ⇒ the constraint
 * (entity level) and the concurrent-race 23505; (4) leaving releases the
 * slot and rejoining works; (5) the switch is atomic — the
 * delete+flush+insert ride one transaction, so a create failure leaves
 * the old membership intact (the @MockitoSpyBean seam injects the
 * failure at the repository boundary and the REAL rollback answers);
 * (6) the export carries the membership (active and left) and the purge
 * touches nothing (the documented adapter exception); plus the house
 * guards: Envers revisions for every state flip, the 401-anonymous seam,
 * and the DB CHECK floor for raw writers.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodMembershipModuleIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): levels 0..3 of the
    // REAL administrative tree the service gates against.
    private static final String SYRIA = "11111111-1111-4111-8111-111111111101";
    private static final String RIF_DIMASHQ = "11111111-1111-4111-8111-111111111102";
    private static final String QUDSAYYA_CITY = "11111111-1111-4111-8111-111111111103";
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";
    private static final String AL_HAMAH = "11111111-1111-4111-8111-111111111106";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    /**
     * The atomicity criterion's own seam (criterion 5): a REAL failure
     * injected at the repository boundary — the official Spring Framework
     * {@code @MockitoSpyBean} (the same bean-override family as the
     * house's {@code @MockitoBean}) wraps the real repository; the spy
     * passes everything through except the one stubbed save, and the
     * REAL transaction rollback is what the assertion reads afterward.
     */
    @MockitoSpyBean
    NeighborhoodMembershipRepository membershipRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @Autowired
    private com.marketplace.community.spi.CommunityExportAdapter exportAdapter;

    @Autowired
    private com.marketplace.community.spi.CommunityContentPurgeAdapter purgeAdapter;

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /** Joins the given node over the REAL chain; asserts the given status. */
    private void joinOverHttp(UUID userId, String locationId, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private int activeRows(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_memberships WHERE user_id = ? AND is_deleted = FALSE",
                Integer.class, userId);
    }

    private int totalRows(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_memberships WHERE user_id = ?",
                Integer.class, userId);
    }

    @Test
    void criterion1_joinLevel3_thenReadReturnsIt() throws Exception {
        UUID userId = asCaller(UUID.randomUUID());

        joinOverHttp(userId, QUDSAYYA_OLD_TOWN, 201);
        // the idempotent re-join of the SAME node: 200, the stored row
        // answers — no second row (PUT semantics, end to end)
        joinOverHttp(userId, QUDSAYYA_OLD_TOWN, 200);
        assertThat(totalRows(userId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.locationId").value(QUDSAYYA_OLD_TOWN))
                .andExpect(jsonPath("$.verificationState").value("SELF_DECLARED"))
                .andExpect(jsonPath("$.memberSince").exists());
        assertThat(activeRows(userId)).isEqualTo(1);
    }

    @Test
    void criterion2_nonLevel3OrUnknown_is400Or404BeforeAnyWrite() throws Exception {
        UUID userId = asCaller(UUID.randomUUID());

        // Levels 0, 1 and 2 of the REAL seed tree — the type gate answers
        // 400 before any write.
        joinOverHttp(userId, SYRIA, 400);
        joinOverHttp(userId, RIF_DIMASHQ, 400);
        joinOverHttp(userId, QUDSAYYA_CITY, 400);
        // An unknown node id — the port's own existence gate (404).
        joinOverHttp(userId, UUID.randomUUID().toString(), 404);

        // Criterion 2's closing assertion: nothing was written.
        assertThat(totalRows(userId)).isZero();
    }

    @Test
    void criterion3_secondActiveMembership_isImpossible() {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        // The entity-level floor (the realestate uniqueListingIdConstraint
        // precedent): a second ACTIVE row for the same user is rejected by
        // V60's partial unique index (SQLSTATE 23505 — the shared handler's
        // backstop maps it to 409 at HTTP).
        membershipService.join(userId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        assertThatThrownBy(() -> membershipRepository.saveAndFlush(
                        NeighborhoodMembership.join(userId, UUID.fromString(QUDSAYYA_SUBURB),
                                java.time.Clock.systemUTC())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // a different user's membership is unaffected — the slot is per user
        membershipService.join(otherUser, UUID.fromString(QUDSAYYA_SUBURB));
        assertThat(activeRows(userId)).isEqualTo(1);
        assertThat(activeRows(otherUser)).isEqualTo(1);
    }

    @Test
    void criterion3b_concurrentJoins_neverLeaveTwoActiveRows() throws Exception {
        // The race the backstop exists for: two joins that both read "no
        // membership" and both insert. The CountDownLatch aligns the
        // STARTS, not the interleavings (CodeRabbit round 1, adopted from
        // the root): exactly two interleavings are legal — (a) the true
        // race, both threads pass findByUserId before either commits, and
        // the partial unique index rejects the loser's insert (23505 ⇒ the
        // DataIntegrityViolationException the shared handler maps to 409
        // at HTTP); (b) the threads serialize, the later one SEES the
        // committed row and takes the idempotent branch. The assertion is
        // the invariant that holds in EVERY interleaving: at least one
        // join answers, every failure is the unique-violation backstop
        // itself (never any other exception), and EXACTLY one row exists —
        // active and total. Two active rows (the unserialized race the
        // G-N1 index exists to make impossible) fails the test in every
        // interleaving — which is the point.
        UUID userId = UUID.randomUUID();
        UUID location = UUID.fromString(AL_HAMAH);

        java.util.concurrent.CountDownLatch gate = new CountDownLatch(1);
        Callable<Object> racer = () -> {
            gate.await();
            return membershipService.join(userId, location);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(racer);
            Future<Object> second = executor.submit(racer);
            gate.countDown();
            Object outcome1 = outcomeOf(first);
            Object outcome2 = outcomeOf(second);

            var outcomes = java.util.List.of(outcome1, outcome2);
            long wins = outcomes.stream()
                    .filter(o -> o instanceof NeighborhoodMembershipService.MembershipCommandResult).count();
            // interleaving (a): 1 win + 1 reject; interleaving (b): 2 wins
            // (the second is the idempotent re-join). Both are correct
            // system behavior; neither may ever be anything else.
            assertThat(wins).isBetween(1L, 2L);
            assertThat(outcomes.stream()
                    .filter(o -> !(o instanceof NeighborhoodMembershipService.MembershipCommandResult)))
                    .allMatch(o -> o instanceof org.springframework.dao.DataIntegrityViolationException);
            assertThat(activeRows(userId)).isEqualTo(1);
            assertThat(totalRows(userId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Object outcomeOf(Future<Object> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException execution) {
            return execution.getCause(); // the racer's own exception (the 23505)
        }
    }

    @Test
    void criterion4_leaveReleasesTheSlot_rejoinWorks() throws Exception {
        UUID userId = asCaller(UUID.randomUUID());

        joinOverHttp(userId, QUDSAYYA_OLD_TOWN, 201);
        mockMvc.perform(delete("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(status().isNoContent());
        assertThat(activeRows(userId)).isZero();
        assertThat(totalRows(userId)).isEqualTo(1); // soft-deleted, not erased

        // the released slot: a rejoin inserts a FRESH row (memberSince
        // honestly restarts) and the index admits it.
        joinOverHttp(userId, QUDSAYYA_OLD_TOWN, 201);
        assertThat(activeRows(userId)).isEqualTo(1);
        assertThat(totalRows(userId)).isEqualTo(2);

        // leaving without a membership is an honest 404 (the /me convention)
        mockMvc.perform(delete("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void criterion5_switchIsAtomicAndLevelGatedOnTheNewLocation() throws Exception {
        UUID userId = asCaller(UUID.randomUUID());

        joinOverHttp(userId, QUDSAYYA_OLD_TOWN, 201);
        // the switch: PUT a different level-3 node — 201 (a new row was
        // created), the old one soft-deleted, exactly one active
        joinOverHttp(userId, QUDSAYYA_SUBURB, 201);
        assertThat(activeRows(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_memberships WHERE user_id = ? AND is_deleted = TRUE",
                Integer.class, userId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(jsonPath("$.locationId").value(QUDSAYYA_SUBURB));

        // a switch aimed at a NON-level-3 node never touches the stored
        // membership (the gate precedes every write)
        joinOverHttp(userId, QUDSAYYA_CITY, 400);
        mockMvc.perform(get("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(jsonPath("$.locationId").value(QUDSAYYA_SUBURB));
    }

    @Test
    void criterion5b_createFailure_leavesTheOldMembershipIntact() {
        // The plan's transaction test: the switch's delete + insert ride
        // ONE transaction, so a create failure cannot leave the old
        // membership deleted. The @MockitoSpyBean seam injects a REAL
        // failure at the repository's save; the service's own
        // @Transactional rollback is what the afterward query reads.
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(QUDSAYYA_OLD_TOWN));

        NeighborhoodMembership old = membershipRepository.findByUserId(userId).orElseThrow();
        doThrow(new IllegalStateException("simulated create failure"))
                .when(membershipRepository).save(any(NeighborhoodMembership.class));

        assertThatThrownBy(() ->
                membershipService.join(userId, UUID.fromString(QUDSAYYA_SUBURB)))
                .isInstanceOf(IllegalStateException.class);

        // the rollback restored the old membership — still active, still
        // the SAME row, and no new row exists.
        assertThat(activeRows(userId)).isEqualTo(1);
        assertThat(totalRows(userId)).isEqualTo(1);
        assertThat(membershipRepository.findByUserId(userId).orElseThrow().getId())
                .isEqualTo(old.getId());
    }

    @Test
    void criterion6_exportCarriesTheMembership_purgeTouchesNothingByDesign() {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        membershipService.join(userId, UUID.fromString(QUDSAYYA_SUBURB)); // the switch

        // b-2: the export carries BOTH rows — the left one too (b-5's
        // discrimination: surface deletion is not erasure)
        var entries = exportAdapter.exportForOwner(userId);
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().filter(e -> !e.deleted()))
                .extracting(com.marketplace.shared.api.CommunityMembershipExportEntry::locationId)
                .containsExactly(UUID.fromString(QUDSAYYA_SUBURB));
        assertThat(entries.stream().filter(com.marketplace.shared.api.CommunityMembershipExportEntry::deleted))
                .hasSize(1);
        assertThat(entries.get(0).verificationState()).isEqualTo("SELF_DECLARED");

        // b-3: the documented exception — the membership row is keys and
        // state (no authored texts), so the purge reports zero and the
        // rows survive byte-identically (b-5 governs their retention).
        String before = jdbc.queryForObject(
                "SELECT string_agg(id::text || ':' || verification_state, ',' ORDER BY created_at, id) "
                        + "FROM neighborhood_memberships WHERE user_id = ?", String.class, userId);
        assertThat(purgeAdapter.purgeAuthoredTexts(userId)).isZero();
        String after = jdbc.queryForObject(
                "SELECT string_agg(id::text || ':' || verification_state, ',' ORDER BY created_at, id) "
                        + "FROM neighborhood_memberships WHERE user_id = ?", String.class, userId);
        assertThat(after).isEqualTo(before);
        assertThat(exportAdapter.exportForOwner(userId)).hasSize(2);
    }

    @Test
    void envers_everyMembershipLeavesItsTrailOnTheRealMirror() throws Exception {
        UUID userId = asCaller(UUID.randomUUID());

        joinOverHttp(userId, QUDSAYYA_OLD_TOWN, 201);       // row A created
        joinOverHttp(userId, QUDSAYYA_SUBURB, 201);         // row A soft-deleted + row B created (the switch)
        mockMvc.perform(delete("/api/v1/me/neighborhood").with(jwt()))
                .andExpect(status().isNoContent());          // row B soft-deleted (the leave)

        // The V24/_aud convention guard (the geo module's own house form):
        // every membership row's creation left its revision — the audit
        // trail exists for the moderation/export surfaces to read. The
        // exact DEL-vs-MOD shape of a @SoftDelete flip is L42's own
        // criterion to pin (its plan needs it explicitly); this anchor
        // layer pins the trail's existence.
        UUID rowA = jdbc.queryForObject(
                "SELECT id FROM neighborhood_memberships WHERE user_id = ? AND is_deleted = TRUE "
                        + "ORDER BY created_at, id LIMIT 1", UUID.class, userId);
        UUID rowB = jdbc.queryForObject(
                "SELECT id FROM neighborhood_memberships WHERE user_id = ? AND is_deleted = TRUE "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1", UUID.class, userId);
        assertThat(membershipRepository.findRevisions(rowA,
                org.springframework.data.domain.Pageable.unpaged()).getContent()).isNotEmpty();
        assertThat(membershipRepository.findRevisions(rowB,
                org.springframework.data.domain.Pageable.unpaged()).getContent()).isNotEmpty();

        // and the V60 mirror itself carries both rows' audited columns
        Integer mirrorRows = jdbc.queryForObject(
                "SELECT count(DISTINCT id) FROM neighborhood_memberships_aud WHERE id IN (?, ?)",
                Integer.class, rowA, rowB);
        assertThat(mirrorRows).isEqualTo(2);
    }

    @Test
    void anonymousSurface_is401ThroughTheRealResourceServerChain() throws Exception {
        // The security seam (the §15 smoke's own probe): every membership
        // endpoint is authenticated — 401 problem+json for the anonymous
        // caller, zero security-config change for this layer.
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + QUDSAYYA_OLD_TOWN + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/neighborhood"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/me/neighborhood"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkConstraint_backsTheVerificationFloorForRawWriters() {
        UUID userId = UUID.randomUUID();
        var view = membershipService.join(userId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        UUID rowId = view.view().id();

        // A raw SQL writer that bypasses the entity floor cannot invent a
        // verification state (D-N3: VERIFIED is reserved behind G-N2) —
        // PostgreSQL SQLSTATE 23514 with V60's constraint name.
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                jdbc.update("UPDATE neighborhood_memberships SET verification_state = 'VERIFIED' "
                        + "WHERE id = ?", rowId));
        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(root.getMessage()).contains("chk_neighborhood_memberships_verification_state");
    }
}
