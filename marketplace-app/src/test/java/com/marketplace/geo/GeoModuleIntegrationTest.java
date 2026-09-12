package com.marketplace.geo;

import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    private GeoLocationRepository repository;

    /** A random-slug subtree (never collides with the seed or other tests). */
    private GeoLocation seedOwnSubtree() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        GeoLocation country = repository.save(GeoLocation.createRoot(
                "بلد-" + salt, "Country-" + salt, "c-" + salt));
        GeoLocation governorate = repository.save(GeoLocation.createChild(
                country, "محافظة-" + salt, null, "g-" + salt));
        GeoLocation city = repository.save(GeoLocation.createChild(
                governorate, "مدينة-" + salt, null, "ci-" + salt));
        repository.save(GeoLocation.createChild(city, "حي1-" + salt, null, "n1-" + salt));
        repository.save(GeoLocation.createChild(city, "حي2-" + salt, null, "n2-" + salt));
        return city;
    }

    @Test
    void tree_containsTheSeedChainRootToNeighborhoods() {
        GeoNode tree = geoService.getTree();

        GeoNode syria = findChild(tree, "syria");
        assertThat(syria).isNotNull();
        assertThat(syria.level()).isZero();
        GeoNode rif = findChild(syria, "rif-dimashq");
        assertThat(rif).isNotNull();
        assertThat(rif.level()).isEqualTo(1);
        GeoNode qudsayya = findChild(rif, "qudsayya");
        assertThat(qudsayya).isNotNull();
        assertThat(qudsayya.level()).isEqualTo(2);
        assertThat(qudsayya.children()).extracting(GeoNode::slug)
                .contains("qudsayya-old-town", "qudsayya-suburb", "al-hamah");
        assertThat(qudsayya.children()).allMatch(node -> node.level() == 3);
    }

    private static GeoNode findChild(GeoNode parent, String slug) {
        return parent.children().stream()
                .filter(child -> child.slug().equals(slug))
                .findFirst().orElse(null);
    }

    @Test
    void suggest_byArabicPrefix_findsTheSeededCity() {
        assertThat(geoService.suggest("قد"))
                .extracting(GeoNode::slug)
                .contains("qudsayya", "qudsayya-old-town", "qudsayya-suburb");
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
        GeoLocation city = seedOwnSubtree();
        GeoLocation governorate = repository.findById(city.getParentId()).orElseThrow();
        GeoLocation country = repository.findById(governorate.getParentId()).orElseThrow();

        Set<UUID> fromCountry = geoService.findSelfAndDescendants(country.getId());
        assertThat(fromCountry).hasSize(5);

        Set<UUID> fromCity = geoService.findSelfAndDescendants(city.getId());
        assertThat(fromCity).hasSize(3);
        assertThat(fromCity).contains(city.getId());
    }

    @Test
    void findSelfAndDescendants_seededCity_coversItsNeighborhoods() {
        GeoLocation qudsayya = repository.findBySlug("qudsayya").orElseThrow();

        Set<UUID> fromCity = geoService.findSelfAndDescendants(qudsayya.getId());

        assertThat(fromCity).hasSize(4); // the city + 3 seeded neighborhoods
        assertThat(fromCity).contains(qudsayya.getId());
    }

    @Test
    void findSelfAndDescendants_unknownLocation_is404() {
        assertThatThrownBy(() -> geoService.findSelfAndDescendants(UUID.randomUUID()))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreate_appendsTheChildAndLeavesAnAuditTrail() {
        GeoLocation city = seedOwnSubtree();

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
        GeoLocation city = seedOwnSubtree();

        assertThatThrownBy(() -> geoService.delete(city.getId()))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class);
        assertThat(repository.findById(city.getId())).isPresent();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_childless_softDeletes() {
        GeoLocation city = seedOwnSubtree();
        GeoLocation neighborhood = repository
                .findByParentIdOrderBySlugAsc(city.getId()).get(0);

        geoService.delete(neighborhood.getId());

        assertThat(repository.findById(neighborhood.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void adminCommands_asNonAdmin_are403() {
        GeoLocation city = seedOwnSubtree();

        assertThatThrownBy(() -> geoService.createChild(city.getId(), "حي", null,
                "x-" + UUID.randomUUID().toString().substring(0, 8)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> geoService.update(city.getId(), "مدينة", null, city.getSlug()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> geoService.delete(city.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void uniqueSlugConstraint_rejectsDuplicatesAtTheDatabase() {
        assertThatThrownBy(() -> repository.saveAndFlush(
                GeoLocation.createRoot("سوريا", "Syria", "syria")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
