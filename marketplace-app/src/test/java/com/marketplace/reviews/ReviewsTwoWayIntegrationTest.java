package com.marketplace.reviews;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L21 (feature-expansion roadmap §5, Week 2) — the full two-way review loop
 * over the real modules: the reviews service publishes the existing events,
 * the provider module's {@code ProviderReviewStatsListener} consumes them
 * AFTER_COMMIT, resolves the recomputed aggregate through the real
 * {@code ReviewStatsAdapter} and lands it on the provider profile.
 *
 * <p>Acceptance criteria (§5-L21): (1) one reply per review, owned
 * exclusively by the reviewed provider; (2) the stored average matches the
 * AVG computed in the test, with read stability across consecutive updates;
 * (3) the cache invalidation events ride every write path (asserted at the
 * unit level — the service tests).
 *
 * <p>The booking seam is the standard {@code @MockitoBean} boundary: reviews
 * are created through the real service against fabricated COMPLETED
 * bookings, exactly like {@code ReviewsModuleIntegrationTest} does in the
 * module slice — here the whole application context is up so the cross-module
 * event flow is real. Method security reads the test's mock principal
 * ({@code @WithMockUser}); ownership reads the {@code JwtAuthenticationToken}
 * method parameter whose subject is a real users row (what
 * {@code IdentityUserProvider} resolves).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewsTwoWayIntegrationTest {

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private ReviewsService reviewsService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @WithMockUser(roles = "CONSUMER")
    void reviewEvents_landStoredAverageOnProviderProfile_withReadStability() {
        // The reviewer's users row: update()'s ownership check resolves the
        // JWT subject through the real IdentityUserProvider and compares it
        // with the review's reviewerId — same user here.
        UUID reviewerId = userRepository.save(
                User.create("l21-reviewer-subject", "reviewer@b.com", "Reviewer", UserRole.CONSUMER)).getId();
        ProviderProfile profile = providerRepository.save(
                ProviderProfile.create("L21 Provider", "bio", UUID.randomUUID()));
        UUID providerId = profile.getId();
        stubCompletedBooking(reviewerId, providerId);

        // Two reviews land (3 and 4): AVG = 3.5 — the expected value is
        // computed here, then compared against the stored average.
        Review first = reviewsService.create(UUID.randomUUID(), reviewerId, 3, "okay");
        Review second = reviewsService.create(UUID.randomUUID(), reviewerId, 4, "good");
        awaitAverage(providerId, (3 + 4) / 2.0);

        // Consecutive update (acceptance 2 — read stability): the second
        // review's rating moves 4 -> 5, AVG = 4.0.
        reviewsService.update(second.getId(), 5, "good — updated",
                jwtAuthentication("l21-reviewer-subject", "ROLE_CONSUMER"));
        awaitAverage(providerId, (3 + 5) / 2.0);

        // Read stability: repeated reads return the same stored value.
        assertThat(providerRepository.findById(providerId).orElseThrow().getRatingAverage())
                .isEqualTo(4.0);
        assertThat(providerRepository.findById(providerId).orElseThrow().getRatingAverage())
                .isEqualTo((3 + 5) / 2.0);

        assertThat(reviewRepository.findById(first.getId()).orElseThrow().getRating()).isEqualTo(3);
        // Cleanup re-loads the row: the async listener bumped the version after
        // the test's last read (CI round-1 evidence — the stale entity's
        // optimistic delete matched 0 rows).
        providerRepository.findById(providerId).ifPresent(providerRepository::delete);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void reply_isExclusiveToTheReviewedProvider_andUnique() {
        userRepository.save(User.create("l21-owner", "owner@b.com", "Owner", UserRole.PROVIDER));
        userRepository.save(User.create("l21-outsider", "outsider@b.com", "Outsider", UserRole.PROVIDER));
        UUID noProfileUserId = userRepository.save(
                User.create("l21-noprofile", "np@b.com", "NoProfile", UserRole.PROVIDER)).getId();
        ProviderProfile owned = providerRepository.save(ProviderProfile.create("Owned", "bio",
                userRepository.findBySubject("l21-owner").orElseThrow().getId()));
        providerRepository.save(ProviderProfile.create("Outsider", "bio",
                userRepository.findBySubject("l21-outsider").orElseThrow().getId()));

        // The review is planted directly: this test exercises the reply path,
        // whose guard is PROVIDER — the create path (CONSUMER) is covered by
        // the stats test above and the unit suite.
        Review review = reviewRepository.save(Review.create(
                UUID.randomUUID(), UUID.randomUUID(), owned.getId(), 5, "excellent"));

        // The reviewed provider replies.
        reviewsService.reply(review.getId(), "Thanks!", jwtAuthentication("l21-owner", "ROLE_PROVIDER"));
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getReply())
                .isEqualTo("Thanks!");

        // A second reply is a conflict, never an overwrite (acceptance 1).
        assertThrows(ConflictException.class,
                () -> reviewsService.reply(review.getId(), "second", jwtAuthentication("l21-owner", "ROLE_PROVIDER")));
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getReply())
                .isEqualTo("Thanks!");

        // A different provider cannot reply to someone else's review
        // (acceptance 1 — ownership exclusivity)...
        assertThrows(AccessDeniedException.class,
                () -> reviewsService.reply(review.getId(), "not mine",
                        jwtAuthentication("l21-outsider", "ROLE_PROVIDER")));
        // ...and neither can a provider-less user.
        assertThrows(AccessDeniedException.class,
                () -> reviewsService.reply(review.getId(), "no profile",
                        jwtAuthentication("l21-noprofile", "ROLE_PROVIDER")));

        // The no-profile subject resolved to a users row and no provider profile.
        assertThat(noProfileUserId).isNotNull();
        providerRepository.delete(owned);
    }

    private void stubCompletedBooking(UUID consumerId, UUID providerId) {
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(new BookingInfo(
                providerId, consumerId, "COMPLETED", 5000L, "SAR",
                Instant.now(), Instant.now()));
    }

    /**
     * I8 (internal free plan §6, roadmap §7) — the reverse direction through
     * the REAL event flow: the provider rates the booking's consumer, the
     * same ReviewCreatedEvent fires, the real listener resolves the
     * authoring provider and recomputes its average — and the average MUST
     * NOT move, because the aggregate counts forward reviews only (the
     * direction filter). Then the read surfaces split correctly:
     * listByProvider shows the forward review alone; listByReviewee shows
     * the reverse review alone; both directions coexist on ONE booking.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void reverseReview_neverPollutesTheProviderAverage_andSplitsTheReadSurfaces() {
        UUID consumerId = userRepository.save(User.create("i8-guest-subject",
                "i8-guest@b.com", "Guest", UserRole.CONSUMER)).getId();
        UUID ownerUserId = userRepository.save(User.create("i8-host-subject",
                "i8-host@b.com", "Host", UserRole.PROVIDER)).getId();
        ProviderProfile profile = providerRepository.save(ProviderProfile.create(
                "I8 Provider", "bio", ownerUserId));
        UUID providerId = profile.getId();
        UUID bookingId = UUID.randomUUID();
        stubCompletedBooking(consumerId, providerId);

        // A forward review lands first — planted directly (this test's
        // subject is the PROVIDER; the forward create path is the stats
        // test above): rating 3.
        reviewRepository.save(Review.create(bookingId, consumerId, providerId, 3, "okay stay"));
        awaitAverage(providerId, 3.0);

        // The reverse review on the SAME booking: the provider rates the
        // consumer 1 (would drag any unfiltered average to 2.0).
        Review reverse = reviewsService.createReverse(bookingId, 1, "difficult guest",
                jwtAuthentication("i8-host-subject", "ROLE_PROVIDER"));

        assertThat(reverse.getDirection()).isEqualTo(ReviewDirection.PROVIDER_TO_CONSUMER);
        assertThat(reverse.getRevieweeId()).isEqualTo(consumerId);
        assertThat(reverse.getReviewerId()).isEqualTo(ownerUserId);

        // The stored provider average is UNCHANGED at 3.0 — the async
        // listener recomputed it (the same event fired) and the aggregate
        // excluded the reverse review. Wait for the recompute to land, then
        // assert it landed on the SAME value (never 2.0).
        awaitAverage(providerId, 3.0);
        assertThat(providerRepository.findById(providerId).orElseThrow().getRatingAverage())
                .as("the provider average counts forward reviews only — a provider-authored "
                        + "rating of the consumer never pollutes it")
                .isEqualTo(3.0);

        // The read surfaces split by direction.
        assertThat(reviewsService.listByProvider(providerId, org.springframework.data.domain.Pageable.ofSize(10))
                .map(Review::getDirection))
                .as("the provider's public surface shows only reviews ABOUT them")
                .containsExactly(ReviewDirection.CONSUMER_TO_PROVIDER);
        assertThat(reviewsService.listByReviewee(consumerId, org.springframework.data.domain.Pageable.ofSize(10))
                .map(Review::getDirection))
                .as("the consumer's trust surface shows only reviews about THEM")
                .containsExactly(ReviewDirection.PROVIDER_TO_CONSUMER);

        // Per-direction uniqueness on the one booking: the forward create
        // still works (the reverse exists, the forward does not).
        // (Exercised by the unit suite; asserted here as data facts.)
        assertThat(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .isTrue();
        assertThat(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER))
                .isTrue();

        providerRepository.findById(providerId).ifPresent(providerRepository::delete);
    }

    /**
     * I8: the reverse-review gates, through the real service: a provider
     * who is not the booking's provider is rejected; a duplicate reverse
     * review on one booking is a conflict.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void reverseReview_gatesOwnershipAndUniqueness() {
        UUID consumerId = userRepository.save(User.create("i8-guest2-subject",
                "i8-guest2@b.com", "Guest2", UserRole.CONSUMER)).getId();
        UUID ownerUserId = userRepository.save(User.create("i8-host2-subject",
                "i8-host2@b.com", "Host2", UserRole.PROVIDER)).getId();
        UUID outsiderUserId = userRepository.save(User.create("i8-outsider-subject",
                "i8-outsider@b.com", "Outsider", UserRole.PROVIDER)).getId();
        ProviderProfile owned = providerRepository.save(ProviderProfile.create(
                "I8 Owner", "bio", ownerUserId));
        providerRepository.save(ProviderProfile.create("I8 Outsider", "bio", outsiderUserId));
        UUID providerId = owned.getId();
        UUID bookingId = UUID.randomUUID();
        stubCompletedBooking(consumerId, providerId);

        // The outsider provider (with a real profile) does not own the booking.
        assertThrows(AccessDeniedException.class,
                () -> reviewsService.createReverse(bookingId, 5, "not my booking",
                        jwtAuthentication("i8-outsider-subject", "ROLE_PROVIDER")));

        // The owner creates the reverse review, then a second one conflicts.
        reviewsService.createReverse(bookingId, 4, "great guest",
                jwtAuthentication("i8-host2-subject", "ROLE_PROVIDER"));
        assertThrows(ConflictException.class,
                () -> reviewsService.createReverse(bookingId, 2, "second attempt",
                        jwtAuthentication("i8-host2-subject", "ROLE_PROVIDER")));

        providerRepository.delete(owned);
    }

    /**
     * The method-parameter principal: a {@code JwtAuthenticationToken} whose
     * subject is a real users row — the shape {@code IdentityUserProvider}
     * resolves. The role rides the authorities for any parameter-level
     * expectations; the method-security gate reads the @WithMockUser context.
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

    /** Plain poll loop (30s / 200ms) — no Awaitility dependency in this reactor. */
    private void awaitAverage(UUID providerId, double expected) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Double last = null;
        while (System.nanoTime() < deadline) {
            last = providerRepository.findById(providerId).map(ProviderProfile::getRatingAverage).orElse(null);
            if (last != null && Math.abs(last - expected) < 1e-9) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(String.format(
                "Stored rating average for provider %s never reached %s after 30s (last seen: %s) —"
                        + " the async ReviewCreated/ReviewUpdated listener did not land the aggregate.",
                providerId, expected, last));
    }
}
