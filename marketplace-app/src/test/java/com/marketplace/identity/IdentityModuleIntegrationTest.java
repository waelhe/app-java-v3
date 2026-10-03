package com.marketplace.identity;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class IdentityModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    // L23: UserService now consumes the framework-managed UserDetailsManager
    // (SecurityConfig bean in platform-infra) — outside this module slice, so
    // the standard @MockitoBean pattern (house convention, see
    // ReviewsModuleIntegrationTest) applies.
    @MockitoBean
    UserDetailsManager userDetailsManager;

    // I7 Phase 1: UserService also consumes SubjectPseudonymizer — a
    // platform-infra shared-security component, likewise outside this module
    // slice. The mock's default (isConfigured() = false) is the honest slice
    // shape: the tombstone probe is inert when the secret channel is unbound.
    @MockitoBean
    com.marketplace.shared.security.SubjectPseudonymizer subjectPseudonymizer;

    // I7 Phase 2: UserController's aggregation service consumes the five
    // cross-module export ports (shared-api contracts implemented by the
    // booking/reviews/messaging/media/notifications modules — outside this
    // module slice). The house @MockitoBean pattern for outside-slice ports,
    // exactly like SubjectPseudonymizer above; the full aggregation contract
    // is the integration guard's job, not the slice's.
    @MockitoBean
    com.marketplace.shared.api.BookingExportPort bookingExportPort;

    @MockitoBean
    com.marketplace.shared.api.ReviewExportPort reviewExportPort;

    @MockitoBean
    com.marketplace.shared.api.MessagingExportPort messagingExportPort;

    @MockitoBean
    com.marketplace.shared.api.MediaExportPort mediaExportPort;

    @MockitoBean
    com.marketplace.shared.api.NotificationExportPort notificationExportPort;

    // L35 (realestate systems plan §5): the export's sixth section — the
    // saved-search port (the search module's adapter, outside this slice);
    // the same house pattern as the five ports above.
    @MockitoBean
    com.marketplace.shared.api.SavedSearchExportPort savedSearchExportPort;

    // L41 (neighborhood community plan §5): the export's seventh section —
    // the community module's membership port (its adapter lives outside
    // this slice); the same house pattern as the six ports above.
    @MockitoBean
    com.marketplace.shared.api.CommunityExportPort communityExportPort;

    // W3 (G19, the review round's export leg): the favorites section — the
    // catalog module's ListingFavoritesExportPort (its adapter lives
    // outside this slice); the same house pattern as the seven ports
    // above. The CI-measured round: without this mock the context boot
    // fails on UserDataExportService's constructor.
    @MockitoBean
    com.marketplace.shared.api.ListingFavoritesExportPort listingFavoritesExportPort;

    // W4 (G28): the public reviewer page's stats seam — the reviews module's
    // ReviewerStatsPort (its adapter lives outside this slice); the same
    // house pattern as the ports above. The CI-measured family round (W3's
    // slice failures): without this mock the context boot fails on
    // ReviewerPublicProfileService's constructor.
    @MockitoBean
    com.marketplace.shared.api.ReviewerStatsPort reviewerStatsPort;

    // W4 (G21): the follow domain's write path resolves the client-facing
    // provider profile through ProviderLookupPort (the provider module's
    // adapter, outside this slice); the same house pattern. The batch form
    // the my-follows page composes rides the same port.
    @MockitoBean
    com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    // I7 Phase 3: the purge orchestration (AuthoredContentPurgeService, in
    // this module slice) consumes the cross-module purge port as a List —
    // the shared-api contract implemented by the six owning modules'
    // adapters (booking/messaging/reviews/disputes/notifications/provider,
    // outside this module slice). One mock satisfies the list injection
    // (the Phase 2 five-port precedent, collapsed by the List shape); the
    // full fan-out contract is the integration guard's job, not the
    // slice's.
    @MockitoBean
    com.marketplace.shared.api.AuthoredContentPurgePort authoredContentPurgePort;

    // I7 Phase 3 gate b-4: the audit-history orchestration
    // (AuditHistoryPurgeService, in this module slice) consumes the
    // infrastructure-level scrub adapter — a com.marketplace.shared.jpa
    // component in platform-infra (the audit columns' own convention
    // home). The slice scans this module's package only, so the adapter
    // is not a bean here — the standard @MockitoBean pattern for
    // outside-slice dependencies (exactly like SubjectPseudonymizer
    // above). The full scrub contract is the integration guard's job.
    @MockitoBean
    com.marketplace.shared.jpa.AuditColumnScrubAdapter auditColumnScrubAdapter;

    @Autowired
    private UserService userService;

    @Test
    void contextLoads() {
    }

    @Test
    void findAll_returnsEmptyPage() {
        var page = userService.findAll(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    @Test
    void getById_throwsForUnknown() {
        assertThrows(ResourceNotFoundException.class, () -> userService.getById(UUID.randomUUID()));
    }
}
