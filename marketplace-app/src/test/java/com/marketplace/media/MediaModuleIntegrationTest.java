package com.marketplace.media;

import test.config.IntegrationContainers;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the documented inert state: with no MEDIA_S3_* credentials bound (the
 * default test profile), the module boots cleanly and every media operation
 * answers 503 SU-001 — the capability is off, not broken. This is the same
 * provider-gate semantics as MAIL (SYSTEM.md §15 debt item 3).
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(test.config.ModuleTestConfig.class)
@WithMockUser(roles = "PROVIDER")
class MediaModuleIntegrationTest {

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
    ListingPriceProvider listingPriceProvider;

    @MockitoBean
    com.marketplace.shared.api.ListingPublicStatePort listingPublicStatePort;

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    /** L48: the post-target seam — mocked at the media module slice (community implements it in the full app). */
    @MockitoBean
    PostLookupPort postLookupPort;

    @Autowired
    private MediaService mediaService;

    @Test
    void contextLoadsWithoutStorageBeans() {
        // boots green with an empty ObjectProvider<S3MediaStorage> by design
    }

    @Test
    void requestUpload_whenUnconfigured_answers503() {
        assertThatThrownBy(() ->
                mediaService.requestUpload(UUID.randomUUID(), "image/jpeg", 1024L, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Media storage is not configured");
    }

    @Test
    void listByListing_whenUnconfigured_andNoRows_answersTheHonestEmptyList() {
        // The union of both contracts on the merged chain: the R5 privacy
        // gate rides the public path (the port's lenient stub — the gate is
        // not this round's concern), THEN the 2026-10-01 measured contract:
        // the query runs FIRST and requireStorage only when rows exist — a
        // photo-less public listing's read never touches the channel, so an
        // unconfigured storage degrades to the honest empty gallery instead
        // of failing the whole surface. (The retired unconditional-503 pin
        // was the pre-fix storage-first ordering; the rows-exist 503 is
        // pinned by the security net's seeded-row proof.)
        org.mockito.Mockito.lenient()
                .when(listingPublicStatePort.isPubliclyVisible(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        assertThat(mediaService.listByListing(UUID.randomUUID(), null)).isEmpty();
    }

    @Test
    void confirmUpload_whenUnconfigured_answers503() {
        assertThatThrownBy(() -> mediaService.confirmUpload(UUID.randomUUID(), null))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void delete_whenUnconfigured_answers503() {
        assertThatThrownBy(() -> mediaService.delete(UUID.randomUUID(), null))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
