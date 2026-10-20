package com.marketplace.app.graphql;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the catalog owner's real Spring Data JPA keyset query against PostgreSQL.
 * A newly inserted row ahead of the cursor must not shift the continuation window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StoreProductKeysetPaginationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final Sort PRODUCT_ORDER =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    @Autowired
    private ProductRepository products;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional
    void continuationRemainsStableWhenANewProductIsInsertedAheadOfTheCursor() {
        UUID providerId = UUID.randomUUID();
        String categoryCode = "keyset-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), categoryCode, "Keyset test", "اختبار التصفح");

        List<Product> original = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> products.save(Product.register(
                        categoryCode, "Original " + i, "Pagination fixture", 1000L + i,
                        "SAR", providerId)))
                .toList();
        entityManager.flush();

        // Give the fixture a deterministic, non-null sort key on the exact
        // columns used by the query/index: created_at DESC, id DESC.
        for (int i = 0; i < original.size(); i++) {
            Instant createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i);
            jdbcTemplate.update("UPDATE products SET created_at = ? WHERE id = ?",
                    Timestamp.from(createdAt), original.get(i).getId());
        }
        entityManager.clear();

        Page<Product> baseline = products.findByProviderId(
                providerId, PageRequest.of(0, 10, PRODUCT_ORDER));
        List<UUID> expectedIds = baseline.getContent().stream().map(Product::getId).toList();
        assertThat(expectedIds).hasSize(5);

        Window<Product> first = products.findByProviderIdOrderByCreatedAtDescIdDesc(
                providerId, ScrollPosition.keyset(), Limit.of(2));
        assertThat(first.getContent()).extracting(Product::getId)
                .containsExactlyElementsOf(expectedIds.subList(0, 2));
        assertThat(first.hasNext()).isTrue();

        KeysetScrollPosition continuation = (KeysetScrollPosition) first.positionAt(first.size() - 1);

        Product newHead = products.save(Product.register(
                categoryCode, "Inserted ahead of cursor", "Must not shift continuation",
                2000L, "SAR", providerId));
        entityManager.flush();
        jdbcTemplate.update("UPDATE products SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")), newHead.getId());
        entityManager.clear();

        Window<Product> second = products.findByProviderIdOrderByCreatedAtDescIdDesc(
                providerId, continuation.forward(), Limit.of(2));

        assertThat(second.getContent()).extracting(Product::getId)
                .containsExactlyElementsOf(expectedIds.subList(2, 4));
        assertThat(second.getContent()).extracting(Product::getId)
                .doesNotContain(newHead.getId());
        assertThat(second.getContent()).extracting(Product::getId)
                .doesNotContainAnyElementsOf(first.getContent().stream().map(Product::getId).toList());
        assertThat(second.hasNext()).isTrue();
    }
}
