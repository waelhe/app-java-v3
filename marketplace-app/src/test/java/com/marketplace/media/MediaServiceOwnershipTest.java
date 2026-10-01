package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

/**
 * L48 — the target-aware OWNERSHIP gates of the media commands, unit-pinned
 * with a LIVE storage seam: this context carries an {@code S3MediaStorage}
 * mock BEAN the way the media module's integration tests do
 * ({@code MediaUploadFlowIntegrationTest} house pattern), so the calls ride
 * past {@code requireStorage()} onto the real rows and the ownership
 * resolution itself. (The sibling {@code MediaServiceSecurityTest} pins the
 * role gates on the inert seam — the two contexts split exactly where the
 * L48 gate split: roles on one side, ownership on the other.)
 *
 * <p>The two rules under test (the L48 design, from the code's own facts):
 * a LISTING asset resolves ownership through the provider profile (A1), a
 * POST asset through the DIRECT user-id compare (the author is a member —
 * no provider profile involved); the admin pass survives on both for the
 * moderation surface.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { MediaService.class, MediaServiceOwnershipTest.TestConfig.class })
@EnableMethodSecurity(proxyTargetClass = true)
class MediaServiceOwnershipTest {

    @Autowired
    private MediaService mediaService;

    /** The storage bean the service's ObjectProvider resolves (the live seam). */
    @MockitoBean
    private S3MediaStorage storage;

    @MockitoBean
    private MediaAssetRepository mediaAssetRepository;

    @MockitoBean
    private ListingPriceProvider listingPriceProvider;

    /**
     * The R5 publication-state seam (main's wave 4) the merged MediaService
     * constructor requires — this ownership slice never routes a listing
     * GALLERY read, so the mock rides inert (the @MockitoBean boundary the
     * other ports above already form; the full-context tests exercise the
     * real catalog implementation).
     */
    @MockitoBean
    private com.marketplace.shared.api.ListingPublicStatePort listingPublicStatePort;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @MockitoBean
    private PostLookupPort postLookupPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Configuration
    static class TestConfig {
        @Bean
        MediaProperties mediaProperties() {
            return new MediaProperties(
                    new MediaProperties.Storage("", "auto", "", "", "", false),
                    new MediaProperties.Limits(10_485_760L,
                            Set.of("image/jpeg", "image/png"), Duration.ofMinutes(15), 640, 25_000_000L));
        }

        @Bean
        MediaThumbnailMetrics mediaThumbnailMetrics() {
            return new MediaThumbnailMetrics(new SimpleMeterRegistry());
        }
    }

    /**
     * L48: a caller holding no claim on the EXISTING listing asset is denied
     * by the provider-profile resolution (A1) — the gate that replaced the
     * blanket PROVIDER role on confirm. An unknown asset answers the honest
     * 404 instead (pinned in the module unit net).
     */
    @Test
    @WithMockUser(roles = "USER")
    void confirmUpload_listingAsset_whenNotOwner_thenAccessDenied() {
        UUID owner = UUID.randomUUID();
        MediaAsset asset = MediaAsset.create(
                UUID.randomUUID(), owner, "listings/x/y.jpg", "image/jpeg", 1L, 1);
        when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(currentUserProvider.getCurrentUserId(null)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(null)).thenReturn(false);
        // no provider profile resolves the caller's claim on the owner's id
        // (unstubbed findByUserId answers Optional.empty) ⇒ the resolution denies

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.confirmUpload(asset.getId(), null));
    }

    /**
     * L48: the POST target's own rule — a member who is NOT the post's author
     * is denied by the DIRECT user-id compare, no provider-profile resolution
     * involved (a member author need not hold a provider profile at all).
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void confirmUpload_postAsset_whenNotAuthor_thenAccessDenied() {
        MediaAsset asset = MediaAsset.createForPost(
                UUID.randomUUID(), UUID.randomUUID(), "posts/x/y.jpg", "image/jpeg", 1L, 1);
        when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(currentUserProvider.getCurrentUserId(null)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(null)).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.confirmUpload(asset.getId(), null));
    }

    /**
     * L48: the author's own confirm passes EVERY gate (role: authenticated
     * member; ownership: the direct user-id compare) and reaches the storage
     * verification itself — the unstubbed HeadObject check answers its honest
     * 400 ("Object not found in storage"), the proof the whole member path
     * is open for a CONSUMER holding no provider profile.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void confirmUpload_postAsset_byAuthor_reachesTheStorageVerification() {
        UUID author = UUID.randomUUID();
        MediaAsset asset = MediaAsset.createForPost(
                UUID.randomUUID(), author, "posts/x/y.jpg", "image/jpeg", 1L, 1);
        when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(currentUserProvider.getCurrentUserId(null)).thenReturn(author);
        when(currentUserProvider.isAdmin(null)).thenReturn(false);
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 1L)).thenReturn(false);

        assertThatExceptionOfType(BadRequestException.class).isThrownBy(
                () -> mediaService.confirmUpload(asset.getId(), null));
    }

    /**
     * L48: the delete gate mirrors confirm's — the authority is the
     * target-aware ownership, not the blanket role pair; a CONSUMER with no
     * claim on the EXISTING listing asset is denied.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void delete_listingAsset_whenNotOwnerOrAdmin_thenAccessDenied() {
        UUID owner = UUID.randomUUID();
        MediaAsset asset = MediaAsset.create(
                UUID.randomUUID(), owner, "listings/x/y.jpg", "image/jpeg", 1L, 1);
        when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(currentUserProvider.getCurrentUserId(null)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(null)).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.delete(asset.getId(), null));
    }
}
