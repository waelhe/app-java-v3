package com.marketplace.identity;

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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class IdentityModuleIntegrationTest {

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
