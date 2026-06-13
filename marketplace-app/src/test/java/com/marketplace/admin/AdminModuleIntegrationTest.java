package com.marketplace.admin;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.BookingParticipantProvider;
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

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ProviderNameResolver providerNameResolver;

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
