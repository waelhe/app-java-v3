package com.marketplace.reviews;

import com.marketplace.booking.BookingParticipantProviderAdapter;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The §9 surgical gate fix's integration proof (account-pseudonymization
 * plan §9 — the A1 live defect row): the two review write gates,
 * {@code ReviewsService.reply} and {@code createReverse}, through the REAL
 * {@code BookingParticipantProviderAdapter} — NO stub on the booking seam
 * (the plan's letter: «اختبار تكاملي عبر المحوّل الحقيقي
 * (BookingParticipantProviderAdapter لا stub)»).
 *
 * <p><b>Why this test exists:</b> the measured live defect — the gates
 * compared the stored {@code users.id} (A1: {@code reviews.provider_id} /
 * {@code bookings.provider_id} physically reference {@code users(id)},
 * V6/V3) against the RESOLVED {@code provider_profiles.id}
 * ({@code providerLookupPort.findByUserId(...).id()}), so the legitimate
 * owner was denied on every call: no reply, no reverse review, ever. The
 * previous tests hid it by seeding/mocking with the profile id (coinciding
 * the two id spaces). This test seeds the PRODUCTION shape — real booking
 * rows carrying the provider's USER id — and asserts the legitimate owner
 * PASSES (the defect's exact counter-proof) and everyone else is denied.
 *
 * <p><b>The gates after the fix (the {@code verifyProviderOwnership}
 * pattern, BookingService):</b> the stored row is the ruling, compared
 * directly against the caller's user id — no profile resolution anywhere
 * (the {@code ProviderLookupPort} dependency is gone from the service).
 *
 * <p><b>Seeding (the Phase 3 b-3 JDBC pattern, real FK chains):</b> users
 * rows (the provider, the consumer, an outsider), the provider's listing
 * (V2: {@code provider_listings.provider_id} references {@code users(id)}),
 * and COMPLETED bookings whose {@code provider_id} IS the provider's user
 * id (V3's FK truth — what {@code BookingParticipantProviderAdapter}
 * returns through the port at runtime).
 */
@SpringBootTest(properties = {
        // The production-shaped schema (the CatalogSearchFullText / b-3 house
        // pattern): real Flyway V1..V46, no ddl-auto — the default test
        // profile's create-drop schema lacks the migration DEFAULTS the
        // JDBC seeds rely on (V2's is_deleted default false) and V30's
        // revinfo sequence alignment; the §7 "test schema != production
        // schema" class, measured in CI round 1.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewsOwnershipGateIntegrationTest {

    // NO @MockitoBean BookingParticipantProvider — the REAL adapter reads
    // the real booking rows this test seeds. The §9 letter.

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private ReviewsService reviewsService;

    @Autowired
    private BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(roles = {"CONSUMER", "PROVIDER"})
    void reply_gateThroughTheRealAdapter_theLegitimateOwnerPasses() {
        GateFixture fx = seedCompletedBooking("gatefix-r1");

        // The REAL adapter is wired (mechanically checked — the §9 letter:
        // no stub on this seam).
        assertThat(bookingParticipantProvider)
                .isInstanceOf(BookingParticipantProviderAdapter.class);

        // The forward review lands through the REAL create path (the
        // consumer reads his own booking row through the same adapter).
        Review forward = reviewsService.create(fx.bookingId(), fx.consumerId(), 5, "great stay");
        assertThat(forward.getProviderId())
                .as("the write path stores the booking provider's USER id (A1)")
                .isEqualTo(fx.providerUserId());

        // THE DEFECT'S COUNTER-PROOF: the reviewed provider (whose USER id
        // the row carries) replies — through the real adapter, this exact
        // call was ALWAYS 403 before the fix.
        Review replied = reviewsService.reply(forward.getId(), "Thanks for staying!",
                jwtAuthentication(fx.providerSubject(), "ROLE_PROVIDER"));
        assertThat(replied.getReply()).isEqualTo("Thanks for staying!");
        assertThat(replied.getRepliedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reply FROM reviews WHERE id = ?", String.class, forward.getId()))
                .isEqualTo("Thanks for staying!");

        // The exclusivity holds on the REAL ruling: the review's author
        // (the consumer) and an outsider provider are both denied — their
        // user ids are not the stored provider user id.
        assertThrows(AccessDeniedException.class,
                () -> reviewsService.reply(forward.getId(), "self reply",
                        jwtAuthentication(fx.consumerSubject(), "ROLE_CONSUMER")));
        assertThrows(AccessDeniedException.class,
                () -> reviewsService.reply(forward.getId(), "not mine",
                        jwtAuthentication(fx.outsiderSubject(), "ROLE_PROVIDER")));

        // Uniqueness by construction (the entity's own guard).
        assertThrows(ConflictException.class,
                () -> reviewsService.reply(forward.getId(), "second",
                        jwtAuthentication(fx.providerSubject(), "ROLE_PROVIDER")));
    }

    @Test
    @WithMockUser(roles = {"CONSUMER", "PROVIDER"})
    void createReverse_gateThroughTheRealAdapter_theBookingProviderPasses() {
        GateFixture fx = seedCompletedBooking("gatefix-r2");

        // A provider who is not the booking's provider is denied by the
        // row's ruling — asserted BEFORE the successful call: the service
        // checks the duplicate guard first, so after a reverse review
        // exists every caller would get the Conflict instead (CodeRabbit
        // round-1, adopted from the root).
        assertThrows(AccessDeniedException.class,
                () -> reviewsService.createReverse(fx.bookingId(), 5, "not my booking",
                        jwtAuthentication(fx.outsiderSubject(), "ROLE_PROVIDER")));

        // THE DEFECT'S COUNTER-PROOF: the booking's provider (whose USER id
        // the booking row carries, read through the REAL adapter) writes
        // the reverse review — always 403 before the fix.
        Review reverse = reviewsService.createReverse(fx.bookingId(), 4, "great guest",
                jwtAuthentication(fx.providerSubject(), "ROLE_PROVIDER"));
        assertThat(reverse.getDirection()).isEqualTo(ReviewDirection.PROVIDER_TO_CONSUMER);
        assertThat(reverse.getRevieweeId()).isEqualTo(fx.consumerId());
        assertThat(reverse.getReviewerId()).isEqualTo(fx.providerUserId());
        assertThat(reverse.getProviderId())
                .as("the authoring provider stored in the A1 space")
                .isEqualTo(fx.providerUserId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT direction FROM reviews WHERE id = ?", String.class, reverse.getId()))
                .isEqualTo("PROVIDER_TO_CONSUMER");

        // Per-direction uniqueness on the REAL booking row.
        assertThrows(ConflictException.class,
                () -> reviewsService.createReverse(fx.bookingId(), 2, "second attempt",
                        jwtAuthentication(fx.providerSubject(), "ROLE_PROVIDER")));
    }

    /**
     * Seeds the production shape: three users, the provider's listing (V2
     * FK), and ONE COMPLETED booking whose {@code provider_id} is the
     * provider's USER id (V3 FK) — exactly what the real adapter returns
     * through {@code BookingParticipantProvider.getBookingInfo} at runtime.
     */
    private GateFixture seedCompletedBooking(String tag) {
        User provider = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "Provider", UserRole.PROVIDER));
        User consumer = userRepository.save(User.create(
                tag + "-consumer-subject", tag + "-consumer@t.com", "Consumer", UserRole.CONSUMER));
        userRepository.save(User.create(
                tag + "-outsider-subject", tag + "-outsider@t.com", "Outsider", UserRole.PROVIDER));

        UUID listingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                listingId, provider.getId(), "Gate Fix Listing " + tag,
                "ownership gate fixture", "home", 100_00L);

        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'COMPLETED', 100_00, 'SAR', NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                bookingId, consumer.getId(), provider.getId(), listingId);

        return new GateFixture(bookingId, provider.getId(), consumer.getId(),
                tag + "-provider-subject", tag + "-consumer-subject",
                tag + "-outsider-subject");
    }

    private record GateFixture(UUID bookingId, UUID providerUserId, UUID consumerId,
                               String providerSubject, String consumerSubject,
                               String outsiderSubject) {
    }

    /**
     * The method-parameter principal: a {@code JwtAuthenticationToken}
     * whose subject is a real users row — the shape
     * {@code IdentityUserProvider} resolves (the ReviewsTwoWayIntegrationTest
     * house pattern).
     */
    private static JwtAuthenticationToken jwtAuthentication(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}
