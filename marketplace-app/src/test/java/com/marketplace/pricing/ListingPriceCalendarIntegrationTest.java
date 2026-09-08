package com.marketplace.pricing;

import com.marketplace.booking.Booking;
import com.marketplace.booking.BookingService;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L26 (feature-expansion roadmap §5, Week 3) — the dynamic pricing loop
 * over the REAL modules: V41 is the schema the loop runs on (Flyway
 * enabled, {@code ddl-auto=none} — the {@code AuditedWrites} /
 * {@code NotificationPreferences} boot convention on an isolated
 * {@code postgres:18-alpine} container), the calendar CRUD is the real
 * {@code ListingPriceCalendarService} against the real tables, and the
 * booking seam is the REAL {@code BookingService} wired to the REAL
 * {@code PricingService} through the shared {@code EffectivePricePort}
 * — no mock between the booking and PostgreSQL on the pricing path.
 *
 * <p>Acceptance criteria (§5-L26):
 * <ol>
 *   <li>the two numeric examples — (أ) Thu→Sun with weekend 1.2 and no
 *       ranges: effective 32 000, quote total 36 800 with the existing
 *       default tax; (ب) a [Thu, Fri) seasonal range at 20 000: effective
 *       42 000 (precedence — the multiplier never stacks on the seasonal
 *       price), quote total 48 300;</li>
 *   <li>overlap policy — a real one-day overlap is 409
 *       (ConflictException), two adjacent ranges sharing a boundary are
 *       accepted (open intervals), and the update path enforces the same
 *       policy excluding the row itself;</li>
 *   <li>byte-compatibility — a listing with NO calendar rows books at the
 *       flat listing price, exactly the pre-L26 number (the roadmap's
 *       most important criterion);</li>
 *   <li>the writes leave Envers revisions on the @Audited V41 tables and
 *       evict the {@code pricing-calculations} cache after commit.</li>
 * </ol>
 * {@code CurrentUserProvider} is the only {@code @MockitoBean} seam (the
 * L22 convention) — the ownership checks are driven through it. The
 * availability check rides the REAL stack too (the L27 pattern): a free
 * slot is seeded per listing owner — {@code AvailabilityPort} must NOT be
 * mocked here because its implementation IS the concrete
 * {@code AvailabilityService} bean (no dedicated adapter), so a type
 * replacement would starve {@code AvailabilityController} of the concrete
 * bean and break the full context boot.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ListingPriceCalendarIntegrationTest {

    private static final long BASE = 10_000L;
    /** Thu 2026-01-15T14:00Z → Sun 2026-01-18T11:00Z (exclusive end). */
    private static final Instant CHECK_IN = Instant.parse("2026-01-15T14:00:00Z");
    private static final Instant CHECK_OUT = Instant.parse("2026-01-18T11:00:00Z");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the house convention (this testcontainers version ships a non-generic PostgreSQLContainer).
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private ListingPriceCalendarService calendarService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private ListingWeekendRuleRepository weekendRuleRepository;

    @Autowired
    private SeasonalRateRepository seasonalRateRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID seedListing(UUID ownerId) {
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, category, price_cents, currency, status)
                VALUES (?, ?, 'L26 suite', 'services', ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, ownerId, BASE);
        return listingId;
    }

    /** Bookings carry {@code consumer_id REFERENCES users(id)} (V3) — a real row is required. */
    private UUID seedConsumer() {
        UUID consumerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, consumerId, "l26-" + consumerId, "l26-" + consumerId + "@example.com", "L26 Consumer");
        return consumerId;
    }

    /**
     * A free slot covering the stay window — the REAL availability path
     * (V15): booked=false and a strict overlap with [CHECK_IN, CHECK_OUT);
     * no time-off rows are seeded, so isAvailable answers true.
     */
    private void seedFreeAvailabilitySlot(UUID providerId) {
        jdbc.update("""
                INSERT INTO availability_slots (id, provider_id, starts_at, ends_at, booked, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, FALSE, now(), now(), 0)
                ON CONFLICT (id) DO NOTHING
                """, UUID.randomUUID(), providerId,
                Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-19T00:00:00Z"));
    }

    private UUID seedVerifiedProviderOwner() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, userId, "l26-" + userId, "l26-" + userId + "@example.com", "L26 Host");
        jdbc.update("""
                INSERT INTO provider_profiles (id, display_name, status, user_id, created_at, updated_at, version)
                VALUES (?, ?, 'VERIFIED', ?, now(), now(), 0)
                ON CONFLICT (id) DO NOTHING
                """, UUID.randomUUID(), "L26 Host", userId);
        return userId;
    }

    private void actingAs(UUID userId, boolean admin) {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(userId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(admin);
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /**
     * Acceptance 1 (أ) + the booking seam: the real PricingService reads
     * the real V41 rows — effective 32 000 (Thu + Fri at base, Sat ×1.2),
     * quote total 36 800 (default 15% tax on top), and the REAL
     * BookingService stores the effective total for the stay window.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void roadmapExampleA_weekendOnlyThroughTheRealStack() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);

        calendarService.upsertWeekendRule(listingId, new BigDecimal("1.2"), currentAuthentication());

        var quote = pricingService.calculatePrice(listingId, BASE, "services", CHECK_IN, CHECK_OUT);
        assertThat(quote.basePriceCents()).isEqualTo(32_000L);
        assertThat(quote.totalCents()).isEqualTo(36_800L);
        assertThat(pricingService.calculateBookingTotalCents(listingId, BASE, CHECK_IN, CHECK_OUT))
                .isEqualTo(32_000L);

        seedFreeAvailabilitySlot(owner);
        Booking booking = bookingService.create(seedConsumer(), listingId, CHECK_IN, CHECK_OUT, null);
        assertThat(booking.getPriceCents()).isEqualTo(32_000L);
        assertThat(booking.getCurrency()).isEqualTo("SAR");
    }

    /**
     * Acceptance 1 (ب): the seasonal range [Thu, Fri) at 20 000 REPLACES
     * the base for Thursday (the multiplier never stacks on it) — Friday
     * base, Saturday ×1.2: effective 42 000, quote 48 300.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void roadmapExampleB_seasonalPrecedenceThroughTheRealStack() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);

        calendarService.upsertWeekendRule(listingId, new BigDecimal("1.2"), currentAuthentication());
        calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L,
                currentAuthentication());

        var quote = pricingService.calculatePrice(listingId, BASE, "services", CHECK_IN, CHECK_OUT);
        assertThat(quote.basePriceCents()).isEqualTo(42_000L);
        assertThat(quote.totalCents()).isEqualTo(48_300L);
    }

    /**
     * Acceptance 3 — the roadmap's MOST IMPORTANT criterion: a listing
     * with no calendar rows books at the flat price, byte-identical to the
     * pre-L26 path, through the real booking seam.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void noCalendarRows_booksAtTheFlatPrice_byteCompatible() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);

        assertThat(pricingService.calculateBookingTotalCents(listingId, BASE, CHECK_IN, CHECK_OUT))
                .isEqualTo(BASE);

        seedFreeAvailabilitySlot(owner);
        Booking booking = bookingService.create(seedConsumer(), listingId, CHECK_IN, CHECK_OUT, null);
        assertThat(booking.getPriceCents()).isEqualTo(BASE);
    }

    /**
     * Acceptance 2 — the overlap policy: adjacent ranges sharing a
     * boundary are ACCEPTED (open intervals); a real one-day overlap on
     * create AND on update is 409 (ConflictException).
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void overlapPolicy_adjacentLegal_realOverlapRejectedWith409() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);
        Authentication auth = currentAuthentication();

        // Adjacent on the shared boundary Jan 15 — legal.
        SeasonalRateResponse first = calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 20_000L, auth);
        SeasonalRateResponse second = calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-20"), 25_000L, auth);

        // A range overlapping `second` by ONE day (Jan 19) — 409.
        assertThatThrownBy(() -> calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-01-19"), LocalDate.parse("2026-01-25"), 18_000L, auth))
                .isInstanceOf(ConflictException.class);

        // Moving `first` forward to overlap `second` — 409 on the update path.
        assertThatThrownBy(() -> calendarService.updateSeasonalRate(listingId, first.id(),
                LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-16"), 20_000L, auth))
                .isInstanceOf(ConflictException.class);

        // Re-tuning `first` in place (disjoint from `second`) — fine, and the
        // calendar view reflects exactly the two live ranges.
        calendarService.updateSeasonalRate(listingId, first.id(),
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 22_000L, auth);
        ListingCalendarResponse calendar = calendarService.getCalendar(listingId, auth);
        assertThat(calendar.seasonalRates()).hasSize(2);
        assertThat(calendar.seasonalRates())
                .extracting(SeasonalRateResponse::priceCents)
                .containsExactly(22_000L, 25_000L);

        // Delete one range — the live set shrinks (soft delete: hidden row,
        // Envers history kept).
        calendarService.deleteSeasonalRate(listingId, second.id(), auth);
        assertThat(calendarService.getCalendar(listingId, auth).seasonalRates()).hasSize(1);
    }

    /**
     * Ownership semantics — resolution and authorization are two checks:
     * an unknown listing is 404 (ResourceNotFoundException through the
     * catalog-owned port); a listing owned by ANOTHER user is 403
     * (AccessDeniedException — the MediaService convention); the admin
     * passes.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void ownership_unknownListing404_foreignListing403_adminPasses() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        UUID stranger = UUID.randomUUID();

        actingAs(owner, false);
        calendarService.upsertWeekendRule(listingId, new BigDecimal("1.1"), currentAuthentication());

        actingAs(stranger, false);
        assertThatThrownBy(() -> calendarService.getCalendar(listingId, currentAuthentication()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> calendarService.upsertWeekendRule(listingId, new BigDecimal("1.5"),
                currentAuthentication()))
                .isInstanceOf(AccessDeniedException.class);
        // The stranger's write never landed: still the owner's 1.1.
        assertThat(weekendRuleRepository.findByListingId(listingId).orElseThrow().getMultiplier())
                .isEqualByComparingTo(new BigDecimal("1.1"));

        actingAs(stranger, true);
        ListingCalendarResponse asAdmin = calendarService.getCalendar(listingId, currentAuthentication());
        assertThat(asAdmin.weekendRule().multiplier()).isEqualByComparingTo(new BigDecimal("1.1"));

        actingAs(owner, false);
        assertThatThrownBy(() -> calendarService.getCalendar(UUID.randomUUID(), currentAuthentication()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The weekend-rule lifecycle: upsert (create, then re-tune), soft
     * delete, and — the V41 partial-unique-index proof — RE-CREATION after
     * the soft delete must not collide with the hidden row.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void weekendRuleLifecycle_recreateAfterSoftDelete() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);
        Authentication auth = currentAuthentication();

        WeekendRuleResponse created = calendarService.upsertWeekendRule(listingId, new BigDecimal("1.2"), auth);
        assertThat(created.multiplier()).isEqualByComparingTo(new BigDecimal("1.2"));

        WeekendRuleResponse reTuned = calendarService.upsertWeekendRule(listingId, new BigDecimal("1.5"), auth);
        assertThat(reTuned.id()).as("upsert re-tunes the single live row").isEqualTo(created.id());
        assertThat(reTuned.multiplier()).isEqualByComparingTo(new BigDecimal("1.5"));

        calendarService.deleteWeekendRule(listingId, auth);
        assertThat(calendarService.getCalendar(listingId, auth).weekendRule()).isNull();

        // The soft-deleted row still occupies the table — the partial unique
        // index (WHERE is_deleted = FALSE) keeps re-creation legal.
        WeekendRuleResponse recreated = calendarService.upsertWeekendRule(listingId, new BigDecimal("1.3"), auth);
        assertThat(recreated.multiplier()).isEqualByComparingTo(new BigDecimal("1.3"));
        assertThat(weekendRuleRepository.findByListingId(listingId).orElseThrow().getMultiplier())
                .isEqualByComparingTo(new BigDecimal("1.3"));
    }

    /**
     * Acceptance 4 — Envers: the @Audited V41 tables carry a revision per
     * write (create + re-tune for the weekend rule; create + update for
     * the range), and the entity snapshots carry the changed values.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void enversTraces_everyCalendarWrite() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);
        Authentication auth = currentAuthentication();

        WeekendRuleResponse rule = calendarService.upsertWeekendRule(listingId, new BigDecimal("1.2"), auth);
        calendarService.upsertWeekendRule(listingId, new BigDecimal("1.5"), auth);
        var ruleRevisions = weekendRuleRepository.findRevisions(rule.id(), Pageable.unpaged());
        assertThat(ruleRevisions.getContent()).hasSize(2);
        assertThat(ruleRevisions.getContent().getFirst().getEntity().getMultiplier())
                .isEqualByComparingTo(new BigDecimal("1.2"));
        assertThat(ruleRevisions.getContent().getLast().getEntity().getMultiplier())
                .isEqualByComparingTo(new BigDecimal("1.5"));

        SeasonalRateResponse rate = calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-10"), 24_000L, auth);
        calendarService.updateSeasonalRate(listingId, rate.id(),
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-08"), 26_000L, auth);
        var rateRevisions = seasonalRateRepository.findRevisions(rate.id(), Pageable.unpaged());
        assertThat(rateRevisions.getContent()).hasSize(2);
        assertThat(rateRevisions.getContent().getLast().getEntity().getPriceCents()).isEqualTo(26_000L);
        assertThat(rateRevisions.getContent().getLast().getEntity().getToDate())
                .isEqualTo(LocalDate.parse("2026-03-08"));
    }

    /**
     * Acceptance 4 — the cache eviction: every calendar write publishes
     * CacheInvalidationRequested (AFTER_COMMIT), so a warmed
     * pricing-calculations entry never outlives a calendar change. The
     * service calls each commit their own transaction (this test method is
     * deliberately NOT @Transactional) — the relay's phase is exercised
     * honestly.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void everyCalendarWrite_evictsThePricingCalculationsCache() {
        UUID owner = seedVerifiedProviderOwner();
        UUID listingId = seedListing(owner);
        actingAs(owner, false);
        Authentication auth = currentAuthentication();

        Cache cache = cacheManager.getCache("pricing-calculations");
        assertThat(cache).isNotNull();

        pricingService.calculatePrice(listingId, BASE, "services", CHECK_IN, CHECK_OUT);
        assertThat(((java.util.Map<?, ?>) cache.getNativeCache())).isNotEmpty();

        // Weekend rule upsert → eviction.
        calendarService.upsertWeekendRule(listingId, new BigDecimal("1.2"), auth);
        assertThat(((java.util.Map<?, ?>) cache.getNativeCache())).isEmpty();

        // Warm again → seasonal create evicts.
        pricingService.calculatePrice(listingId, BASE, "services", CHECK_IN, CHECK_OUT);
        SeasonalRateResponse rate = calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, auth);
        assertThat(((java.util.Map<?, ?>) cache.getNativeCache())).isEmpty();

        // Warm → update evicts.
        pricingService.calculatePrice(listingId, BASE, "services", CHECK_IN, CHECK_OUT);
        calendarService.updateSeasonalRate(listingId, rate.id(),
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-17"), 21_000L, auth);
        assertThat(((java.util.Map<?, ?>) cache.getNativeCache())).isEmpty();

        // Warm → delete evicts.
        pricingService.calculatePrice(listingId, BASE, "services", CHECK_IN, CHECK_OUT);
        calendarService.deleteSeasonalRate(listingId, rate.id(), auth);
        assertThat(((java.util.Map<?, ?>) cache.getNativeCache())).isEmpty();
    }
}
