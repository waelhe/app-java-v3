package com.marketplace.media;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ServiceUnavailableException;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Security enforcement of the media service commands — the house pattern
 * ({@code ReviewsServiceSecurityTest}): the REAL service under
 * {@code @EnableMethodSecurity}, so the @PreAuthorize rules fire exactly as in
 * production. The storage seam is INERT here (no S3MediaStorage bean exists in
 * this context — the service's ObjectProvider resolves nothing), which lets
 * each positive case prove that the gate passed (the call reaches business
 * logic and answers the honest 503) instead of silently short-circuiting.
 *
 * <p><b>L48 — the gate authority moved from roles to target-aware ownership</b>
 * on confirm/delete: the post flow's authors are MEMBERS (a CONSUMER holding
 * no provider profile must confirm their own photo), so those two commands
 * ride {@code isAuthenticated()} + the ownership resolution instead of the
 * blanket PROVIDER role. The ownership DENIALS need a live storage seam and
 * real asset rows — they are pinned in {@code MediaServiceOwnershipTest},
 * which carries an S3MediaStorage mock bean the way the media module's
 * integration tests do.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { MediaService.class, MediaServiceSecurityTest.TestConfig.class })
@EnableMethodSecurity(proxyTargetClass = true)
class MediaServiceSecurityTest {

    @Autowired
    private MediaService mediaService;

    @MockitoBean
    private MediaAssetRepository mediaAssetRepository;

    @MockitoBean
    private ListingPriceProvider listingPriceProvider;

    @MockitoBean
    private com.marketplace.shared.api.ListingPublicStatePort listingPublicStatePort;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    /** L48: the post-target seam — mocked at the media module slice (community implements it in the full app). */
    @MockitoBean
    PostLookupPort postLookupPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Configuration
    static class TestConfig {
        @Bean
        MediaProperties mediaProperties() {
            return new MediaProperties(
                    new MediaProperties.Storage("", "auto", "", "", "", false),
                    new MediaProperties.Limits(10_485_760L, 10,
                            Set.of("image/jpeg", "image/png"), Duration.ofMinutes(15), 640, 25_000_000L));
        }

        /**
         * D3 (I4): the failure counter is a pure observer over a real
         * in-memory registry — nothing to mock (same construction as
         * MediaServiceTest). This narrow context deliberately loads no
         * component scan, so the @Component must be provided explicitly.
         */
        @Bean
        MediaThumbnailMetrics mediaThumbnailMetrics() {
            return new MediaThumbnailMetrics(new SimpleMeterRegistry());
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void requestUpload_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.requestUpload(UUID.randomUUID(), "image/jpeg", 1024L, null));
    }

    /**
     * L48: the member flow is NOT role-blocked — a CONSUMER confirming reaches
     * the business logic (the honest inert 503 here), the proof the post
     * target's confirm path belongs to the member domain (pre-L48 this same
     * call answered 403 from the blanket PROVIDER role).
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void confirmUpload_byMember_reachesBusinessLogic() {
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.confirmUpload(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void requestUpload_whenProvider_thenReachesBusinessLogic() {
        // role gate passed: the call proceeds into the method body and hits the
        // honest inert gate (no storage beans bound), never AccessDenied
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.requestUpload(UUID.randomUUID(), "image/jpeg", 1024L, null));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_whenAdmin_thenReachesBusinessLogic() {
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.delete(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void listByListing_isOpenToAuthenticatedRoles() {
        // read path carries no @PreAuthorize — any authenticated role reaches it
        // (R5: the read is public; the publication-state gate is not a role gate —
        // the lenient true below rides the public path for the null caller).
        // The 2026-10-01 reorder made a no-rows read return the honest empty
        // list WITHOUT asking the (inert) storage seam, so the gate-passed
        // proof now rides one UPLOADED row: the read reaches presigning and
        // answers the honest 503 — never AccessDenied.
        UUID listingId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(listingPublicStatePort.isPubliclyVisible(listingId))
                .thenReturn(true);
        MediaAsset uploaded = MediaAsset.create(listingId, UUID.randomUUID(),
                "listings/" + listingId + "/proof.jpg", "image/jpeg", 1024L, 1);
        uploaded.markUploaded();
        org.mockito.Mockito.when(mediaAssetRepository.findByListingIdAndStatusOrderByPositionAsc(
                listingId, MediaAssetStatus.UPLOADED)).thenReturn(java.util.List.of(uploaded));
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.listByListing(listingId, null));
    }
}
