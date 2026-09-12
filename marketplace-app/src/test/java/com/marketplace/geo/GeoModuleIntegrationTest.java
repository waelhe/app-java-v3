package com.marketplace.geo;

import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.utility.DockerImageName;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L30 integration on the REAL migration schema — the §7 lesson applied from
 * day one: a full application context on an isolated {@code postgres:18}
 * container with Flyway enabled and {@code ddl-auto=none}, so the recursive
 * CTE, the prefix autocomplete and the Envers trail run against exactly the
 * schema V47 + {@code R__seed_geo_qudsaya} produce (a create-drop slice
 * would mask schema drift — the V30/V33 lessons).
 *
 * <p>The seed-tree assertion is containment, not exact equality: every test
 * seeds its own random-slug subtree, so the tree legitimately grows across
 * methods; what must always hold is the seeded chain
 * سوريا → ريف دمشق → قدسيا → three neighborhoods, correctly nested (the
 * acceptance criterion "root to Qudsayya's neighborhoods in one response").
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.Random.class) // order-independence is part of the contract
class GeoModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent (CatalogSearchFullTextIntegrationTest)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private GeoService geoService;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private GeoLocationRepository repository;

    /**
     * A random-slug pair of NEIGHBORHOODS under the SEEDED Qudsayya city
     * (CodeRabbit round 1 adoption: no second country root is ever created —
     * getTree() reads the single seeded root, unordered findFirst would be
     * ambiguous otherwise). Random slugs never collide across tests.
     */
    private GeoLocation seedOwnNeighborhoods() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        GeoLocation qudsayya = repository.findBySlug("qudsayya").orElseThrow();
        repository.save(GeoLocation.createChild(qudsayya, "حي1-" + salt, null, "n1-" + salt));
        repository.save(GeoLocation.createChild(qudsayya, "حي2-" + salt, null, "n2-" + salt));
        return qudsayya;
    }

    @Test
    void tree_containsTheSeedChainRootToNeighborhoods() {
        GeoNode tree = geoService.getTree();

        // The ROOT itself is Syria (level 0) — the single-root contract.
        assertThat(tree.slug()).isEqualTo("syria");
        GeoNode syria = tree;
        assertThat(syria).isNotNull();
        assertThat(syria.level()).isZero();
        GeoNode rif = findChild(syria, "rif-dimashq");
        assertThat(rif).isNotNull();
        assertThat(rif.level()).isEqualTo(1);
        GeoNode qudsayya = findChild(rif, "qudsayya");
        assertThat(qudsayya).isNotNull();
        assertThat(qudsayya.level()).isEqualTo(2);
        // containment: other tests' random-slug neighborhoods accumulate
        // under the seeded qudsayya (shared database, no rollback)
        assertThat(qudsayya.children()).extracting(GeoNode::slug)
                .contains("qudsayya-old-town", "qudsayya-suburb", "al-hamah");
        assertThat(qudsayya.children()).allMatch(node -> node.level() == 3);
    }

    private static GeoNode findChild(GeoNode parent, String slug) {
        return parent.children().stream()
                .filter(child -> child.slug().equals(slug))
                .findFirst().orElse(null);
    }

    // ---- HTTP-level security chain (CodeRabbit round 1 adoption: the
    // production SecurityConfig rules — anonymous geo reads, ADMIN-only
    // writes — exercised on the real filter chain, not a slice mock) ----

    @org.junit.jupiter.api.Test
    void http_tree_isAnonymouslyReadable() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/geo/tree"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.slug").value("syria"));
    }

    @org.junit.jupiter.api.Test
    void http_adminWrite_anonymous_is401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/admin/geo")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void http_adminWrite_nonAdmin_is403() throws Exception {
        // The resource-server chain authenticates BEARER tokens — a mocked
        // JWT with the PROVIDER authority (the house's measured pattern for
        // this chain: a TestingAuthenticationToken is rejected with 401).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/admin/geo")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .jwt().authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PROVIDER")))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void suggest_byArabicPrefix_findsTheNamesStartingWithThePrefix() {
        // The prefix gate is a PREFIX match: "قد" matches قدسيا and
        // قدسيا البلد — NOT ضاحية قدسيا (قد is not its prefix) nor الهامة.
        var slugs = assertThat(geoService.suggest("قد"))
                .extracting(GeoNode::slug);
        slugs.contains("qudsayya", "qudsayya-old-town");
        assertThat(geoService.suggest("قد")).extracting(GeoNode::slug)
                .doesNotContain("qudsayya-suburb", "al-hamah");
    }

    @Test
    void suggest_byLatinPrefix_findsTheSeededGovernorate() {
        assertThat(geoService.suggest("Rif"))
                .extracting(GeoNode::slug)
                .contains("rif-dimashq");
    }

    @Test
    void suggest_belowFloor_is400BeforeAnyQuery() {
        assertThatThrownBy(() -> geoService.suggest("ق"))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
    }

    @Test
    void findSelfAndDescendants_coversTheWholeSubtree() {
        GeoLocation qudsayya = seedOwnNeighborhoods();
        GeoLocation rif = repository.findBySlug("rif-dimashq").orElseThrow();
        GeoLocation syria = repository.findBySlug("syria").orElseThrow();

        // Containment, not exact sizes: tests share the database and other
        // methods' random-slug neighborhoods accumulate under qudsayya.
        Set<UUID> fromCountry = geoService.findSelfAndDescendants(syria.getId());
        assertThat(fromCountry).contains(
                syria.getId(), rif.getId(), qudsayya.getId(),
                UUID.fromString("11111111-1111-4111-8111-111111111104"),
                UUID.fromString("11111111-1111-4111-8111-111111111105"),
                UUID.fromString("11111111-1111-4111-8111-111111111106"));

        Set<UUID> fromCity = geoService.findSelfAndDescendants(qudsayya.getId());
        assertThat(fromCity).contains(qudsayya.getId())
                .contains(UUID.fromString("11111111-1111-4111-8111-111111111104"));
        // the governorate itself is NOT part of the city's subtree
        assertThat(fromCity).doesNotContain(rif.getId());
    }

    @Test
    void findSelfAndDescendants_seededCity_coversItsNeighborhoods() {
        GeoLocation qudsayya = repository.findBySlug("qudsayya").orElseThrow();

        Set<UUID> fromCity = geoService.findSelfAndDescendants(qudsayya.getId());

        assertThat(fromCity).contains(qudsayya.getId(),
                UUID.fromString("11111111-1111-4111-8111-111111111104"),
                UUID.fromString("11111111-1111-4111-8111-111111111105"),
                UUID.fromString("11111111-1111-4111-8111-111111111106"));
    }

    @Test
    void findSelfAndDescendants_unknownLocation_is404() {
        assertThatThrownBy(() -> geoService.findSelfAndDescendants(UUID.randomUUID()))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreate_appendsTheChildAndLeavesAnAuditTrail() {
        GeoLocation city = seedOwnNeighborhoods();

        GeoNode created = geoService.createChild(
                city.getId(), "حي جديد", null, "n3-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(created.level()).isEqualTo(3);
        assertThat(repository.findBySlug(created.slug())).isPresent();
        // Envers: the admin write left a revision (the V24/_aud convention)
        var revisions = repository.findRevisions(created.id(),
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(revisions.getContent()).isNotEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_withChildren_is409() {
        GeoLocation qudsayya = seedOwnNeighborhoods();

        assertThatThrownBy(() -> geoService.delete(qudsayya.getId()))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class);
        assertThat(repository.findById(qudsayya.getId())).isPresent();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_childless_softDeletes() {
        GeoLocation city = seedOwnNeighborhoods();
        GeoLocation neighborhood = repository
                .findByParentIdOrderBySlugAsc(city.getId()).get(0);

        geoService.delete(neighborhood.getId());

        assertThat(repository.findById(neighborhood.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void adminCommands_asNonAdmin_are403() {
        GeoLocation city = seedOwnNeighborhoods();


        assertThatThrownBy(() -> geoService.createChild(city.getId(), "حي", null,
                "x-" + UUID.randomUUID().toString().substring(0, 8)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> geoService.update(city.getId(), "مدينة", null, city.getSlug()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> geoService.delete(city.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void uniqueSlugConstraint_rejectsActiveDuplicatesAtTheDatabase() {
        assertThatThrownBy(() -> repository.saveAndFlush(
                GeoLocation.createChild(repository.findBySlug("qudsayya").orElseThrow(),
                        "قدسيا ثانية", null, "qudsayya")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletedSlug_isReusable_thePartialIndexContract() {
        GeoLocation qudsayya = seedOwnNeighborhoods();
        String salt = UUID.randomUUID().toString().substring(0, 8);
        GeoLocation own = repository.save(GeoLocation.createChild(
                qudsayya, "حي مؤقت", null, "tmp-" + salt));

        geoService.delete(own.getId());
        GeoLocation recreated = repository.saveAndFlush(GeoLocation.createChild(
                qudsayya, "حي معاد", null, "tmp-" + salt));

        assertThat(recreated.getSlug()).isEqualTo("tmp-" + salt);
        assertThat(repository.findBySlug("tmp-" + salt)).isPresent();
    }
}
