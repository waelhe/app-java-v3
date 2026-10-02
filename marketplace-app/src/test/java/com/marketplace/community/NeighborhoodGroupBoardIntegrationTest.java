package com.marketplace.community;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L51 — the groups board's DATABASE-backed guards (the Neighborhood
 * MarketBoardIntegrationTest discipline verbatim: the service tests
 * mock the repository, so the board scoping, the historical sort key,
 * the live grouped count, the soft-delete filtering and V95's
 * one-membership unique index had no test that could see them). This
 * class runs every acceptance fact over the REAL chain: HTTP → the
 * resource-server chain → the membership gate → the REAL geo seed
 * tree → V95's real schema (the partial unique membership index, the
 * partial board index, the Envers mirrors) → the grouped count query
 * PostgreSQL actually compiles.
 *
 * <p>Every test uses its OWN random user ids (the L41/L42 convention —
 * no test-level transaction; each service call commits its own), and
 * the shared container's group rows are wiped between tests (the
 * board's scope is the neighborhood, so an earlier test's rows would
 * bleed into a later board count). The clubs themselves ride raw SQL
 * — the seed's own authoring channel (there is no founding WRITE this
 * wave, the opened G-N6 window's documented product decision), with
 * explicit {@code created_at} literals so the historical order key is
 * deterministic.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The module net pins the CONTRACT LOGIC, not the limiter windows
        // (the events/market tests' own convention): the join/leave
        // cycles below spend the groupMembership window too, so the
        // instance rides a generous test-only budget here.
        "resilience4j.ratelimiter.instances.groupMembership.limit-for-period=100",
        "resilience4j.ratelimiter.instances.groupMembership.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.groupMembership.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodGroupBoardIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): two DIFFERENT level-3
    // neighborhoods — the board-scoping and join-scoping facts need both.
    private static final String OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String SUBURB = "11111111-1111-4111-8111-111111111105";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @BeforeEach
    void isolateGroupData() {
        jdbc.update("DELETE FROM neighborhood_group_memberships_aud");
        jdbc.update("DELETE FROM neighborhood_group_memberships");
        jdbc.update("DELETE FROM neighborhood_groups_aud");
        jdbc.update("DELETE FROM neighborhood_groups");
    }

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /** Joins the given node over the REAL membership service. */
    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    /** Authors a club of the given hood — the seed's own raw-SQL channel. */
    private UUID foundClub(String locationId, String name, String createdAt) {
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhood_groups (id, location_id, name, description, "
                        + "is_deleted, version, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, FALSE, 0, 'it', ?, 'it', ?)",
                groupId, UUID.fromString(locationId), name, "نقاش شهري",
                java.sql.Timestamp.valueOf(createdAt), java.sql.Timestamp.valueOf(createdAt));
        return groupId;
    }

    // ---- (1) the board scoping -------------------------------------------------

    @Test
    void boardScoping_anotherNeighborhoodsClubsNeverAppear() throws Exception {
        asCaller(joinedMember(OLD_TOWN));
        foundClub(SUBURB, "فريق تشجير الضاحية", "2026-07-02 07:30:00");

        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---- (2) the complete historical sort key ------------------------------------

    @Test
    void board_isTheHoodsHistoricalOrder_oldestClubFirst() throws Exception {
        asCaller(joinedMember(OLD_TOWN));
        foundClub(OLD_TOWN, "النادي الثاني", "2026-06-01 10:00:00");
        foundClub(OLD_TOWN, "النادي الأول", "2026-05-01 10:00:00");

        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("النادي الأول"))
                .andExpect(jsonPath("$.content[1].name").value("النادي الثاني"));
    }

    // ---- (3) the join/leave cycle over the real schema ---------------------------

    @Test
    void joinLeaveCycle_theOneMembershipRuleAndBothReaderScopedFacts() throws Exception {
        UUID member = asCaller(joinedMember(OLD_TOWN));
        UUID club = foundClub(OLD_TOWN, "نادي قراء النخيل", "2026-06-07 10:00:00");

        // The join: 201, and the board carries both reader-scoped facts.
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(club.toString()))
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].members").value(1))
                .andExpect(jsonPath("$.content[0].joinedByMe").value(true));

        // The one-membership rule: the second join answers 409 with the
        // contract's own words (the V95 partial unique index is the
        // backstop — the explicit 409 lands first).
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isConflict());

        // The leave: 204, then the honest 404 (nothing left to leave),
        // then the board's facts are back to the open state.
        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].members").value(0))
                .andExpect(jsonPath("$.content[0].joinedByMe").value(false));

        // b-5's retention: the left membership row stays — the reads stop
        // returning it, the audit trail keeps the revision.
        Integer leftRow = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_group_memberships "
                        + "WHERE group_id = ? AND member_id = ? AND is_deleted = TRUE",
                Integer.class, club, member);
        assertThat(leftRow).isEqualTo(1);

        // The freed seat is open for a fresh join (the V95 partial unique
        // index admits exactly that).
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isCreated());
    }

    @Test
    void leave_formerNeighbor_whoSwitchedHoods_takesHisStaleMembershipWithHim() throws Exception {
        // The review round's P1 regression, end to end: the member joins
        // OLD_TOWN's club, then SWITCHES to the suburb (the real
        // membership service's atomic soft-delete+insert — nothing
        // cascades to the group row), then leaves the club. The old
        // shape answered 403 here (the location gate rode the leave)
        // and the stale membership was locked into the count forever;
        // the owner-scoped leave (the /me owner-delete convention)
        // answers 204 and the row is honestly gone from the reads.
        // A RESIDENT reader measures the count (the membership IS the
        // board's scope — the switched member himself now reads the
        // suburb's board).
        UUID reader = joinedMember(OLD_TOWN);
        UUID member = joinedMember(OLD_TOWN);
        UUID club = foundClub(OLD_TOWN, "نادي قراء النخيل", "2026-06-07 10:00:00");
        asCaller(member);
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isCreated());

        // The switch — the REAL service, the REAL atomic pair.
        membershipService.join(member, UUID.fromString(SUBURB));

        // The stale membership is still counted before the leave.
        asCaller(reader);
        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].members").value(1));

        // The leave the old shape refused: 204, and the count is honest.
        asCaller(member);
        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        asCaller(reader);
        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].members").value(0));

        // b-5's retention: the left row stays soft-deleted, the audit
        // trail keeps the revision.
        Integer leftRow = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_group_memberships "
                        + "WHERE group_id = ? AND member_id = ? AND is_deleted = TRUE",
                Integer.class, club, member);
        assertThat(leftRow).isEqualTo(1);
    }

    @Test
    void joinedCount_isTheLiveGroupedAggregate() throws Exception {
        // The reader: an ordinary member of OLD_TOWN who joined nothing.
        UUID reader = joinedMember(OLD_TOWN);
        UUID club = foundClub(OLD_TOWN, "فريق دراجي النخيل", "2026-05-04 06:00:00");
        UUID first = joinedMember(OLD_TOWN);
        UUID second = joinedMember(OLD_TOWN);
        asCaller(first);
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isCreated());
        asCaller(second);
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isCreated());

        // The board's members count is the LIVE grouped aggregate over the
        // real rows (2), and joinedByMe is the reader's own fact (false).
        asCaller(reader);
        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].members").value(2))
                .andExpect(jsonPath("$.content[0].joinedByMe").value(false));
    }

    // ---- (4) the join gates over HTTP --------------------------------------------

    @Test
    void join_crossNeighborhoodMember_is403() throws Exception {
        // Noor of the suburb cannot join an old-town club.
        UUID suburbMember = asCaller(joinedMember(SUBURB));
        UUID club = foundClub(OLD_TOWN, "نادي المشي المسائي", "2026-06-21 16:00:00");

        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isForbidden());
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_group_memberships", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void join_unknownClub_isTheHonest404() throws Exception {
        asCaller(joinedMember(OLD_TOWN));

        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership",
                        UUID.randomUUID()).with(jwt()))
                .andExpect(status().isNotFound());
    }

    // ---- (5) V95's one-membership unique index on raw writers --------------------

    @Test
    void oneMembershipUniqueIndex_firesOnRawWriters() {
        UUID club = foundClub(OLD_TOWN, "ورشة أدوات الجيران", "2026-08-03 17:00:00");
        UUID member = joinedMember(OLD_TOWN);
        jdbc.update("INSERT INTO neighborhood_group_memberships (id, group_id, member_id, "
                + "is_deleted, version, created_by, created_at, updated_by, updated_at) "
                + "VALUES (?, ?, ?, FALSE, 0, 'it', now(), 'it', now())",
                UUID.randomUUID(), club, member);
        try {
            jdbc.update("INSERT INTO neighborhood_group_memberships (id, group_id, member_id, "
                    + "is_deleted, version, created_by, created_at, updated_by, updated_at) "
                    + "VALUES (?, ?, ?, FALSE, 0, 'it', now(), 'it', now())",
                    UUID.randomUUID(), club, member);
            throw new AssertionError("the unique index must reject the duplicate live pair");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected).isNotNull();
        }
    }

    // ---- (6) the retired club's memberships are absent -----------------------------

    @Test
    void retiredClub_answers404AndItsMembershipsAreAbsent() throws Exception {
        UUID member = asCaller(joinedMember(OLD_TOWN));
        UUID club = foundClub(OLD_TOWN, "نادي مؤقت", "2026-09-01 10:00:00");
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isCreated());

        // The club retires (the raw-SQL soft delete — the admin channel's
        // own shape this wave): its board row and its membership gate are
        // both absent exactly as the aggregate's is_deleted semantics say.
        jdbc.update("UPDATE neighborhood_groups SET is_deleted = TRUE WHERE id = ?", club);
        mockMvc.perform(get("/api/v1/neighborhood/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", club)
                        .with(jwt()))
                .andExpect(status().isNotFound());
    }
}
