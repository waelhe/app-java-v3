package com.marketplace.media;

import com.marketplace.shared.api.ListingPriceProvider;
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
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

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
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ListingPriceProvider listingPriceProvider;

    @MockitoBean
    ProviderLookupPort providerLookupPort;

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
    void listByListing_whenUnconfigured_answers503() {
        assertThatThrownBy(() -> mediaService.listByListing(UUID.randomUUID()))
                .isInstanceOf(ServiceUnavailableException.class);
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
