package com.marketplace.reviews;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class ReviewsModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    // The ProviderLookupPort bean is GONE (the §9 surgical gate fix): the
    // reply/createReverse gates read the ruling from the row itself
    // (users.id, A1) — no profile resolution anywhere in the service.

    // W1 (greptile W1 r7, adopted from the root): the expanded
    // ReviewsService constructor resolves four more cross-module seams —
    // the settings port (the reviews.mode gate + the daily cap), the user
    // lookup port (the account-age floor + the reviewer-identity batch),
    // the provider lookup port (the organic target's profile-id → USER-id
    // resolution), and the listing price port (the optional organic
    // listing's provider ownership check). The real adapters live in their
    // own modules and join in the full-context integration tests — at this
    // slice boundary the house @MockitoBean convention applies to each.
    @MockitoBean
    com.marketplace.shared.api.SystemSettingsPort systemSettingsPort;

    @MockitoBean
    com.marketplace.shared.api.UserLookupPort userLookupPort;

    @MockitoBean
    com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    @MockitoBean
    com.marketplace.shared.api.ListingPriceProvider listingPriceProvider;

    /**
     * The W1 Clock — the production bean lives in platform-infra's
     * ClockConfig, which this slice does not scan (the payments module
     * test's D-009 pattern, the catalog module test's exact precedent:
     * one Clock per context, never two).
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ClockBean {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

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
