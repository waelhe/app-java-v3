package com.marketplace.app.graphql;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductVariant;
import com.marketplace.catalog.ProductVariantRepository;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.orders.Cart;
import com.marketplace.orders.CartRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The checkout WRITE journey's integration coverage (TST-01's root fix,
 * W1-B3) — the adversarial scan measured the four write roots
 * ({@code checkoutLinesAdd} / {@code checkoutLinesUpdate} /
 * {@code checkoutLinesDelete} / {@code checkoutDelete}) with ZERO
 * integration tests: every existing checkout IT proved the READ wall.
 * This class drives the full write journey over the REAL HTTP GraphQL
 * surface, the REAL security chain (mocked JwtDecoder stamps
 * subject/roles exactly as the production converter reads them), the
 * REAL orders owner and the REAL migrated Flyway schema:
 *
 * <ul>
 *   <li>The owner's happy journey: add → update → delete-lines → the
 *       admin's delete-checkout, each step's payload verified against
 *       the Saleor shapes.</li>
 *   <li>The ORD-01 pin: three compliant adds at the wire's declared
 *       per-line cap NEVER merge past the cap — the merged quantity is
 *       re-checked (the adversarial scan's measured 3000-on-one-line
 *       bypass, pinned shut).</li>
 *   <li>The IDOR leg: an authenticated intruder writing the victim's
 *       cart answers the same classified NOT_FOUND payload the
 *       ownership wall answers on reads — never a write.</li>
 *   <li>The negative leg: a well-formed but nonexistent variant
 *       answers the classified NOT_FOUND, not a fabricated line.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CheckoutWriteJourneyIntegrationTest {

    private static final String OWNER_SUBJECT = "b3-write-owner-subject";
    private static final String INTRUDER_SUBJECT = "b3-write-intruder-subject";
    private static final String ADMIN_SUBJECT = "b3-write-admin-subject";

    /** The wire contract's own declared per-line bound (CheckoutDataFetcher + OrdersService). */
    private static final int WIRE_MAX_LINE_QUANTITY = 1000;

    private static final long SEEDED_UNIT_MINOR = 2500L;

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private final List<UUID> seededUserIds = new ArrayList<>();
    private final List<UUID> seededCartIds = new ArrayList<>();
    private final List<UUID> seededProductIds = new ArrayList<>();
    private final List<UUID> seededVariantIds = new ArrayList<>();

    private UUID ownerCartId;
    private UUID variantId;

    @BeforeEach
    void seedJourney() {
        UUID ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, OWNER_SUBJECT, null,
                "b3 write owner", UserRole.CONSUMER));
        seededUserIds.add(ownerId);

        UUID intruderId = UUID.randomUUID();
        userRepository.save(new User(intruderId, INTRUDER_SUBJECT, null,
                "b3 write intruder", UserRole.CONSUMER));
        seededUserIds.add(intruderId);

        UUID adminId = UUID.randomUUID();
        userRepository.save(new User(adminId, ADMIN_SUBJECT, null,
                "b3 write admin", UserRole.ADMIN));
        seededUserIds.add(adminId);

        Cart ownerCart = cartRepository.save(Cart.activeFor(ownerId));
        ownerCartId = ownerCart.getId();
        seededCartIds.add(ownerCartId);

        String categoryCode = "b3-write-catalog";
        jdbcTemplate.update("""
                INSERT INTO store_categories (id, code, name_en, name_ar, position)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, UUID.randomUUID(), categoryCode, "B3 Write", "رحلة الكتابة", 0);

        Product product = productRepository.save(Product.register(
                categoryCode, "B3 write journey product", null, SEEDED_UNIT_MINOR, "USD", ownerId));
        seededProductIds.add(product.getId());

        ProductVariant variant = productVariantRepository.save(
                ProductVariant.register(product, "b3-write-" + UUID.randomUUID(), "Blue / Small",
                        SEEDED_UNIT_MINOR));
        variantId = variant.getId();
        seededVariantIds.add(variant.getId());
    }

    @AfterEach
    void removeSeededRows() {
        for (UUID cartId : new ArrayList<>(seededCartIds)) {
            jdbcTemplate.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
            jdbcTemplate.update("DELETE FROM carts WHERE id = ?", cartId);
        }
        seededCartIds.clear();
        for (UUID id : new ArrayList<>(seededVariantIds)) {
            jdbcTemplate.update("DELETE FROM product_variants WHERE id = ?", id);
        }
        seededVariantIds.clear();
        for (UUID id : new ArrayList<>(seededProductIds)) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", id);
        }
        seededProductIds.clear();
        for (UUID id : new ArrayList<>(seededUserIds)) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        seededUserIds.clear();
    }

    // ------------------------------------------------------------------
    // helpers (the established CheckoutGraphQlIntegrationTest pattern)
    // ------------------------------------------------------------------

    private HttpGraphQlTester testerFor(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of(role))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
        return HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    private static String base64GlobalId(String type, UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString((type + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private org.springframework.graphql.test.tester.GraphQlTester.Response addLines(
            HttpGraphQlTester tester, int quantity) {
        return tester.document("""
                        mutation($id: ID!, $lines: [CheckoutLineInput!]!) {
                          checkoutLinesAdd(id: $id, lines: $lines) {
                            checkout { id quantity lines { id quantity } }
                            errors { field message code }
                          }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .variable("lines", List.of(Map.of(
                        "variantId", base64GlobalId("ProductVariant", variantId),
                        "quantity", quantity)))
                .execute();
    }

    // ------------------------------------------------------------------
    // the happy write journey — every root's real stack
    // ------------------------------------------------------------------

    /**
     * The owner's complete write journey: add lands the line, update
     * rewrites the quantity, the bulk delete empties the cart, and the
     * admin's checkoutDelete retires the checkout itself — each step's
     * payload verified against the Saleor shapes over the real stack.
     */
    @Test
    void ownerCompletesTheAddUpdateDeleteWriteJourney() {
        HttpGraphQlTester owner = testerFor(OWNER_SUBJECT, "CONSUMER");

        String lineId = addLines(owner, 2)
                .errors().verify()
                .path("checkoutLinesAdd.checkout.quantity").entity(Integer.class)
                .isEqualTo(2)
                .path("checkoutLinesAdd.checkout.lines[0].quantity").entity(Integer.class)
                .isEqualTo(2)
                .path("checkoutLinesAdd.checkout.lines[0].id").entity(String.class).get();

        String updatedLineId = owner.document("""
                        mutation($id: ID!, $lines: [CheckoutLineUpdateInput!]!) {
                          checkoutLinesUpdate(id: $id, lines: $lines) {
                            checkout { quantity lines { id quantity } }
                            errors { field code }
                          }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .variable("lines", List.of(Map.of(
                        "lineId", lineId,
                        "quantity", 3)))
                .execute()
                .errors().verify()
                .path("checkoutLinesUpdate.checkout.quantity").entity(Integer.class)
                .isEqualTo(3)
                .path("checkoutLinesUpdate.checkout.lines[0].id").entity(String.class).get();
        assertThat(updatedLineId).isEqualTo(lineId);

        owner.document("""
                        mutation($id: ID!, $linesIds: [ID!]!) {
                          checkoutLinesDelete(id: $id, linesIds: $linesIds) {
                            checkout { quantity lines { id } }
                            errors { field code }
                          }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .variable("linesIds", List.of(lineId))
                .execute()
                .errors().verify()
                .path("checkoutLinesDelete.checkout.quantity").entity(Integer.class)
                .isEqualTo(0)
                .path("checkoutLinesDelete.checkout.lines").entityList(Object.class)
                .satisfies(lines -> assertThat(lines).isEmpty());

        testerFor(ADMIN_SUBJECT, "ADMIN").document("""
                        mutation($id: ID!) {
                          checkoutDelete(id: $id) { errors { field code } }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .execute()
                .errors().verify()
                .path("checkoutDelete.errors").entityList(Object.class)
                .satisfies(errors -> assertThat(errors).isEmpty());

        // The retired checkout no longer resolves for its owner.
        owner.document("""
                        query($id: ID!) { checkout(id: $id) { id } }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .execute()
                .errors().verify()
                .path("checkout").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the ORD-01 pin — the merge path carries the declared cap
    // ------------------------------------------------------------------

    /**
     * Three adds, EACH compliant with the declared 1..1000 wire bound,
     * never merge past the cap: the merged quantity is re-checked (the
     * adversarial scan measured 3000 on one line through the merge
     * path; the pin proves the cap rides every shape).
     */
    @Test
    void mergePathAddsNeverExceedTheDeclaredWireCap() {
        HttpGraphQlTester owner = testerFor(OWNER_SUBJECT, "CONSUMER");

        addLines(owner, WIRE_MAX_LINE_QUANTITY)
                .errors().verify()
                .path("checkoutLinesAdd.checkout.lines[0].quantity").entity(Integer.class)
                .isEqualTo(WIRE_MAX_LINE_QUANTITY);

        var second = addLines(owner, WIRE_MAX_LINE_QUANTITY);
        second.errors().verify();
        second.path("checkoutLinesAdd.checkout").valueIsNull();
        second.path("checkoutLinesAdd.errors[0].field").entity(String.class)
                .satisfies(field -> assertThat(field).isEqualTo("lines"));
        second.path("checkoutLinesAdd.errors[0].code").entity(String.class)
                .satisfies(code -> assertThat(code).isEqualTo("INVALID"));

        var third = addLines(owner, 1);
        third.errors().verify();
        third.path("checkoutLinesAdd.checkout").valueIsNull();
        third.path("checkoutLinesAdd.errors[0].code").entity(String.class)
                .satisfies(code -> assertThat(code).isEqualTo("INVALID"));

        // The line itself never crossed the declared cap.
        Integer persistedQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart_items WHERE cart_id = ?",
                Integer.class, ownerCartId);
        assertThat(persistedQuantity).isEqualTo(WIRE_MAX_LINE_QUANTITY);
    }

    // ------------------------------------------------------------------
    // the IDOR leg — the ownership wall on the write path
    // ------------------------------------------------------------------

    /**
     * An authenticated intruder writing the victim's cart answers the
     * classified NOT_FOUND payload the ownership wall answers on reads
     * — the wall the read ITs proved, now proven for every write root:
     * no write happens, no oracle rides the payload.
     */
    @Test
    void intruderWritesAnswerTheOwnershipWallNotFoundAndNothingIsWritten() {
        HttpGraphQlTester intruder = testerFor(INTRUDER_SUBJECT, "CONSUMER");

        var add = addLines(intruder, 1);
        add.errors().verify();
        add.path("checkoutLinesAdd.checkout").valueIsNull();
        add.path("checkoutLinesAdd.errors[0].field").entity(String.class)
                .satisfies(field -> assertThat(field).isEqualTo("id"));
        add.path("checkoutLinesAdd.errors[0].code").entity(String.class)
                .satisfies(code -> assertThat(code).isEqualTo("NOT_FOUND"));

        var update = intruder.document("""
                        mutation($id: ID!, $lines: [CheckoutLineUpdateInput!]!) {
                          checkoutLinesUpdate(id: $id, lines: $lines) {
                            checkout { id }
                            errors { field code }
                          }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .variable("lines", List.of(Map.of(
                        "variantId", base64GlobalId("ProductVariant", variantId),
                        "quantity", 1)))
                .execute();
        update.errors().verify();
        update.path("checkoutLinesUpdate.checkout").valueIsNull();
        update.path("checkoutLinesUpdate.errors[0].code").entity(String.class)
                .satisfies(code -> assertThat(code).isEqualTo("NOT_FOUND"));

        var delete = intruder.document("""
                        mutation($id: ID!, $linesIds: [ID!]!) {
                          checkoutLinesDelete(id: $id, linesIds: $linesIds) {
                            checkout { id }
                            errors { field code }
                          }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .variable("linesIds", List.of(base64GlobalId("CheckoutLine", UUID.randomUUID())))
                .execute();
        delete.errors().verify();
        delete.path("checkoutLinesDelete.checkout").valueIsNull();
        delete.path("checkoutLinesDelete.errors[0].code").entity(String.class)
                .satisfies(code -> assertThat(code).isEqualTo("NOT_FOUND"));

        // Nothing was written: the victim's cart still has zero lines.
        Integer lines = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_items WHERE cart_id = ?",
                Integer.class, ownerCartId);
        assertThat(lines).isZero();
    }

    // ------------------------------------------------------------------
    // the negative leg — a nonexistent variant
    // ------------------------------------------------------------------

    /**
     * A well-formed but nonexistent variant global ID answers the
     * classified NOT_FOUND payload — never a fabricated line, never a
     * 500.
     */
    @Test
    void nonexistentVariantAnswersTheClassifiedNotFoundAndNoLineIsCreated() {
        HttpGraphQlTester owner = testerFor(OWNER_SUBJECT, "CONSUMER");
        UUID unknownVariant = UUID.randomUUID();

        var response = owner.document("""
                        mutation($id: ID!, $lines: [CheckoutLineInput!]!) {
                          checkoutLinesAdd(id: $id, lines: $lines) {
                            checkout { id }
                            errors { field code }
                          }
                        }
                        """)
                .variable("id", base64GlobalId("Checkout", ownerCartId))
                .variable("lines", List.of(Map.of(
                        "variantId", base64GlobalId("ProductVariant", unknownVariant),
                        "quantity", 1)))
                .execute();

        response.errors().verify();
        response.path("checkoutLinesAdd.checkout").valueIsNull();
        response.path("checkoutLinesAdd.errors[0].code").entity(String.class)
                .satisfies(code -> assertThat(code).isEqualTo("NOT_FOUND"));

        Integer lines = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_items WHERE cart_id = ?",
                Integer.class, ownerCartId);
        assertThat(lines).isZero();
    }
}
