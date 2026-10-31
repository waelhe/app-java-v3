package com.marketplace.app.graphql;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductVariant;
import com.marketplace.catalog.ProductVariantRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.orders.CartItem;
import com.marketplace.orders.CartItemRepository;
import com.marketplace.orders.Cart;
import com.marketplace.orders.CartRepository;
import test.config.IntegrationContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * D-UUID-001 (decided by A0 2026-10-23, Option A) — the checkout security
 * evidence gate over the REAL SDL, the REAL DGS wiring, the REAL security
 * chain (the mocked JwtDecoder stamps the caller's subject and roles claim
 * exactly the way the production converter reads them), the REAL orders
 * owner (no SPI mocking: the ownership wall is the real
 * {@code requireOwnedActiveCart} over the real migrated schema) and the
 * REAL typed global-ID codec.
 *
 * <p>The security question the A0 follow-up (upstream Saleor issue #18878)
 * pins: a checkout UUID is a TRANSPORT identifier, never an authentication
 * or authorization grant. The deprecated {@code token} alias and the modern
 * {@code id} converge on the SAME {@code findCheckout} use-case call —
 * proven branch-by-branch by {@code CheckoutDataFetcherTest} — so this
 * class proves the CONVERGED wall end-to-end through the real HTTP
 * surface: the owning consumer reads their own active checkout, an
 * authenticated wrong owner is answered the SAME classified null as a
 * guessed unknown ID (no existence or ownership leakage — the IDOR
 * answer), the ADMIN caller may inspect any consumer's checkout, the
 * guessed well-formed ID answers null without an error, the
 * malformed/raw/mistyped IDs answer the classified VAL-001 validation
 * error, and the ANONYMOUS caller — even carrying a well-formed native
 * UUID token — is answered by the pre-login gate's classified AUTHN-001
 * envelope before GraphQL execution ever starts. The platform's guest
 * policy is explicit: checkout operations have NO anonymous form; the
 * pre-login contract stays exactly as A0 landed it.</p>
 *
 * <p>The assembly on A0's stacked branch now exposes the central
 * {@code scalar UUID}, deprecated {@code token: UUID} aliases, and the
 * direct {@code CheckoutLine.variant: ProductVariant!} contract. This class
 * verifies those declarations against the real HTTP GraphQL runtime and the
 * real catalog/orders modules, not merely a mocked mapper.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CheckoutGraphQlIntegrationTest {

    private static final String OWNER_SUBJECT = "it-checkout-owner-subject";
    private static final String INTRUDER_SUBJECT = "it-checkout-intruder-subject";
    private static final String ADMIN_SUBJECT = "it-checkout-admin-subject";

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
    private CartItemRepository cartItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private final List<UUID> seededUserIds = new ArrayList<>();
    private final List<UUID> seededCartIds = new ArrayList<>();
    private final List<UUID> seededProductIds = new ArrayList<>();
    private final List<UUID> seededVariantIds = new ArrayList<>();
    private UUID ownerUserId;
    private UUID ownerCartId;

    @BeforeEach
    void seedCheckout() {
        // The A1 convention the FK graph demands: every subject the JWTs below
        // carry must resolve to a real users row — the ownership wall reads
        // the caller through IdentityUserProvider's subject lookup.
        UUID ownerId = UUID.randomUUID();
        ownerUserId = ownerId;
        userRepository.save(new User(ownerId, OWNER_SUBJECT, null,
                "it-checkout owner", UserRole.CONSUMER));
        seededUserIds.add(ownerId);

        UUID intruderId = UUID.randomUUID();
        userRepository.save(new User(intruderId, INTRUDER_SUBJECT, null,
                "it-checkout intruder", UserRole.CONSUMER));
        seededUserIds.add(intruderId);

        UUID adminId = UUID.randomUUID();
        userRepository.save(new User(adminId, ADMIN_SUBJECT, null,
                "it-checkout admin reader", UserRole.ADMIN));
        seededUserIds.add(adminId);

        // The account-owned checkout the legs below reason about: the
        // owner's single ACTIVE cart (V113's partial-unique index makes one
        // active cart per consumer the honest shape).
        Cart ownerCart = cartRepository.save(Cart.activeFor(ownerId));
        ownerCartId = ownerCart.getId();
        seededCartIds.add(ownerCartId);
    }

    @AfterEach
    void removeSeededRows() {
        // FK-safe hard deletes (BaseEntity's @SoftDelete would otherwise
        // keep every seeded row alive across the class): the referencing
        // carts go first, then the users rows the carts' consumer ids
        // reference.
        if (!seededCartIds.isEmpty()) {
            for (UUID cartId : new ArrayList<>(seededCartIds)) {
                jdbcTemplate.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
                jdbcTemplate.update("DELETE FROM carts WHERE id = ?", cartId);
            }
            seededCartIds.clear();
        }
        if (!seededVariantIds.isEmpty()) {
            for (UUID variantId : new ArrayList<>(seededVariantIds)) {
                jdbcTemplate.update("DELETE FROM product_variants WHERE id = ?", variantId);
            }
            seededVariantIds.clear();
        }
        if (!seededProductIds.isEmpty()) {
            for (UUID productId : new ArrayList<>(seededProductIds)) {
                jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
            }
            seededProductIds.clear();
        }
        if (!seededUserIds.isEmpty()) {
            for (UUID userId : new ArrayList<>(seededUserIds)) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
            }
            seededUserIds.clear();
        }
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them (authorities claim "roles", prefix ROLE_).
     */
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

    /**
     * Seeds one real product, variant, and cart line so a GraphQL request
     * exercises the direct CheckoutLine.variant: ProductVariant! schema,
     * DGS output mapping, and checkout-context catalog projection together.
     */
    private String seedVariantBackedCartLine() {
        String categoryCode = "it-checkout-graphql";
        jdbcTemplate.update("""
                INSERT INTO store_categories (id, code, name_en, name_ar, position)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, UUID.randomUUID(), categoryCode, "Checkout GraphQL", "واجهة سلة الشراء", 0);

        Product product = productRepository.save(Product.register(
                categoryCode, "Checkout GraphQL test product", null, 2500L, "USD", ownerUserId));
        seededProductIds.add(product.getId());

        String sku = "it-checkout-" + UUID.randomUUID();
        ProductVariant variant = productVariantRepository.save(
                ProductVariant.register(product, sku, "Blue / Small", 2500L));
        seededVariantIds.add(variant.getId());

        cartItemRepository.save(CartItem.of(ownerCartId, product.getId(), variant.getId(),
                2, 2500L, "USD"));
        return sku;
    }

    /** A well-formed typed global ID for the Checkout type over any native UUID. */
    private static String checkoutGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Checkout:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for ANOTHER type — the mistyped-ID leg's own input. */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The base64 prefix every typed Checkout global ID starts with on the wire. */
    private static final String CHECKOUT_ID_PREFIX = java.util.Base64.getEncoder()
            .encodeToString("Checkout:".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // ------------------------------------------------------------------
    // the ownership wall on the converged use case
    // ------------------------------------------------------------------

    @Test
    void ownerReadsCheckoutLineWithDirectProductVariantSelectionOverRealGraphQl() {
        String sku = seedVariantBackedCartLine();
        var response = testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) {
                                  checkout(id: $id) {
                                    lines {
                                      id
                                      quantity
                                      variant {
                                        id
                                        name
                                        sku
                                        status
                                        product { id name status }
                                      }
                                    }
                                  }
                                }
                                """)
                .variable("id", checkoutGlobalId(ownerCartId))
                .execute();

        response.errors().verify();
        response.path("checkout.lines[0].quantity").entity(Integer.class)
                .satisfies(quantity -> assertThat(quantity).isEqualTo(2));
        response.path("checkout.lines[0].variant.id").entity(String.class)
                .satisfies(id -> assertThat(id).startsWith(java.util.Base64.getEncoder()
                        .encodeToString("ProductVariant:".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        response.path("checkout.lines[0].variant.name").entity(String.class)
                .satisfies(name -> assertThat(name).isEqualTo("Blue / Small"));
        response.path("checkout.lines[0].variant.sku").entity(String.class)
                .satisfies(actualSku -> assertThat(actualSku).isEqualTo(sku));
        response.path("checkout.lines[0].variant.status").entity(String.class)
                .satisfies(status -> assertThat(status).isEqualTo("ACTIVE"));
        response.path("checkout.lines[0].variant.product.name").entity(String.class)
                .satisfies(name -> assertThat(name).isEqualTo("Checkout GraphQL test product"));
    }

    @Test
    void ownerReadsTheirOwnActiveCheckoutByTypedGlobalId() {
        var response = testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id token quantity } }
                                """)
                .variable("id", checkoutGlobalId(ownerCartId))
                .execute();

        response.errors().verify();
        response.path("checkout.id").entity(String.class)
                .satisfies(id -> assertThat(id).startsWith(CHECKOUT_ID_PREFIX));
        response.path("checkout.token").entity(String.class)
                .satisfies(token -> assertThat(token).isEqualTo(ownerCartId.toString()));
    }

    @Test
    void ownerReadsTheirOwnActiveCheckoutByDeprecatedUuidToken() {
        var response = testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($token: UUID!) { checkout(token: $token) { id token } }
                                """)
                .variable("token", ownerCartId.toString())
                .execute();

        response.errors().verify();
        response.path("checkout.id").entity(String.class)
                .satisfies(id -> assertThat(id).startsWith(CHECKOUT_ID_PREFIX));
        response.path("checkout.token").entity(String.class)
                .satisfies(token -> assertThat(token).isEqualTo(ownerCartId.toString()));
    }

    @Test
    void authenticatedWrongOwnerCannotReadCheckoutByDeprecatedUuidToken() {
        testerFor(INTRUDER_SUBJECT, "CONSUMER")
                .document("""
                                query($token: UUID!) { checkout(token: $token) { id token } }
                                """)
                .variable("token", ownerCartId.toString())
                .execute()
                .errors().verify()
                .path("checkout").valueIsNull();
    }

    @Test
    void adminMayInspectAnyConsumersCheckoutByDeprecatedUuidToken() {
        var response = testerFor(ADMIN_SUBJECT, "ADMIN")
                .document("""
                                query($token: UUID!) { checkout(token: $token) { id token } }
                                """)
                .variable("token", ownerCartId.toString())
                .execute();

        response.errors().verify();
        response.path("checkout.id").entity(String.class)
                .satisfies(id -> assertThat(id).startsWith(CHECKOUT_ID_PREFIX));
        response.path("checkout.token").entity(String.class)
                .satisfies(token -> assertThat(token).isEqualTo(ownerCartId.toString()));
    }

    @Test
    void guessedButWellFormedUuidTokenAnswersNullForAuthenticatedCaller() {
        testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($token: UUID!) { checkout(token: $token) { id token } }
                                """)
                .variable("token", UUID.randomUUID().toString())
                .execute()
                .errors().verify()
                .path("checkout").valueIsNull();
    }

    @Test
    void authenticatedWrongOwnerIsAnsweredTheSameNullAsAGuessedId() {
        // The IDOR leg: the intruder IS authenticated and the checkout DOES
        // exist — the ownership wall answers the SAME classified null the
        // guessed-ID leg below receives: no existence disclosure, no
        // ownership disclosure, no error.
        testerFor(INTRUDER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id } }
                                """)
                .variable("id", checkoutGlobalId(ownerCartId))
                .execute()
                .errors().verify()
                .path("checkout").valueIsNull();
    }

    @Test
    void adminMayInspectAnyConsumersCheckout() {
        testerFor(ADMIN_SUBJECT, "ADMIN")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id } }
                                """)
                .variable("id", checkoutGlobalId(ownerCartId))
                .execute()
                .errors().verify()
                .path("checkout.id").entity(String.class)
                .satisfies(id -> assertThat(id).startsWith(CHECKOUT_ID_PREFIX));
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id } }
                                """)
                .variable("id", checkoutGlobalId(UUID.randomUUID()))
                .execute()
                .errors().verify()
                .path("checkout").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the typed global-ID codec's classified validation errors
    // ------------------------------------------------------------------

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id } }
                                """)
                .variable("id", "not-a-global-id")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id } }
                                """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void mistypedGlobalIdAnswersTheClassifiedValidationError() {
        testerFor(OWNER_SUBJECT, "CONSUMER")
                .document("""
                                query($id: ID!) { checkout(id: $id) { id } }
                                """)
                .variable("id", productGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    // ------------------------------------------------------------------
    // the anonymous surface: the token alias never reopens it
    // ------------------------------------------------------------------

    @Test
    void anonymousCheckoutLookupByUuidTokenIsAnsweredByThePreLoginGate() {
        // A UUID is an identifier, not a credential. The gate must reject
        // the operation before the token resolver or owner SPI is reached.
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ checkout(token: \\\""
                        + ownerCartId.toString()
                        + "\\\") { id token } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHN-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.data").doesNotExist();
    }

    @Test
    void anonymousCheckoutLookupIsAnsweredByThePreLoginGate() {
        // The guest-policy leg: this platform's checkout operations have NO
        // anonymous form — possession of a checkout's identifier (here the
        // well-formed typed global ID of a REAL account-owned cart) is not
        // authentication, and the pre-login gate answers the classified
        // AUTHN-001 envelope before GraphQL execution ever starts.
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ checkout(id: \\\""
                        + checkoutGlobalId(ownerCartId)
                        + "\\\") { id } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHN-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.data").doesNotExist();
    }
}
