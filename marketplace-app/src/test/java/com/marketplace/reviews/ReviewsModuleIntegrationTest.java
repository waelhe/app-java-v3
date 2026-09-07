package com.marketplace.reviews;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class ReviewsModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    // L21: the reply ownership check resolves the caller's provider profile
    // through the cross-module port — outside this module slice, so the
    // standard @MockitoBean pattern applies (house convention).
    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @Autowired
    private ReviewsService reviewsService;

    @Test
    void contextLoads() {
    }

    @Test
    void listByProvider_returnsEmptyPage() {
        var page = reviewsService.listByProvider(UUID.randomUUID(), Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }
}
