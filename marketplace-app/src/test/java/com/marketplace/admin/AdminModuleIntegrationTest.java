package com.marketplace.admin;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.EffectivePricePort;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class AdminModuleIntegrationTest {

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @MockitoBean
    AvailabilityPort availabilityPort;

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    /**
     * L26: BookingService (wired into this slice through AdminController)
     * consumes the effective-price port whose only implementation
     * (PricingService) lives in the pricing module — outside the slice.
     */
    @MockitoBean
    EffectivePricePort effectivePricePort;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ProviderNameResolver providerNameResolver;

    // I7 Phase 2: this slice's ALL_DEPENDENCIES bootstrap pulls in the
    // identity module (UserController's aggregation service consumes the
    // five export ports), but the ports' implementations live in modules
    // outside the closure — the same house @MockitoBean pattern as the
    // other outside-slice ports above.
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

    @Autowired
    private RevisionService revisionService;

    @Test
    void contextLoads() {
    }

    @Test
    void getEntityNames_returnsEntities() {
        var names = revisionService.getEntityNames();
        assertThat(names).isNotEmpty();
    }

    @Test
    void resolveEntityClass_returnsClassForKnownName() {
        var names = revisionService.getEntityNames();
        var firstName = names.iterator().next();
        var clazz = revisionService.resolveEntityClass(firstName);
        assertThat(clazz).isNotNull();
    }
}
