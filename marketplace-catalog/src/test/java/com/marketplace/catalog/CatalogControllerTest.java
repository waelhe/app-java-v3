package com.marketplace.catalog;

import com.marketplace.shared.api.ListingSummary;import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;

import com.marketplace.shared.api.MediaLookupPort;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Module-local controller unit tests (plan item 4.1 — catalog tests inside
 * their module, the house pattern of {@code GeoControllerTest}: plain
 * Mockito, no Spring context). The Web-layer wiring (security chains,
 * serialization, validation) stays covered by the app-side
 * {@code CatalogControllerWebMvcTest}; THESE tests pin the controller's own
 * composition contracts:
 * <ul>
 *   <li>the L31 batch property embed on the provider browse surface;</li>
 *   <li>the public detail read's composition order — property + JSON-LD
 *       composed BEFORE the view is counted (the CodeRabbit round-1
 *       adoption: a failed composition must not count as a view), and the
 *       remote address riding the request into the fingerprint;</li>
 *   <li>the L38 completeness composition (media count + property through
 *       the leaf ports);</li>
 *   <li>the L33 activation window: an absent body delegates {@code null}
 *       (the expiry policy's contract) while an explicit body passes
 *       through unchanged.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {

    @Mock
    private CatalogService catalogService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private ListingMapper listingMapper;
    @Mock
    private PropertyDetailsPort propertyDetailsPort;
    @Mock
    private MediaLookupPort mediaLookupPort;
    @Mock
    private ListingSeoService listingSeoService;
    @Mock
    private ListingViewCounter listingViewCounter;

    @InjectMocks
    private CatalogController controller;

    private static final UUID LISTING_ID = UUID.randomUUID();

    @Test
    void listActive_servesTheServicePage() {
        PagedResponse<ListingSummary> page = PagedResponse.of(new PageImpl<>(List.of(
                new ListingSummary(LISTING_ID, "شالية مطلة", "stay", BigDecimal.TEN, "SAR", "مزوّن قدسيا"))));
        when(catalogService.listActive(SpringPagination.toPagedRequest(Pageable.unpaged())))
                .thenReturn(page);

        ResponseEntity<PagedResponse<ListingSummary>> result = controller.listActive(Pageable.unpaged());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).extracting(ListingSummary::id).containsExactly(LISTING_ID);
    }

    @Test
    void listByCategory_delegatesTheCategoryAndPage() {
        when(catalogService.listByCategory("stay", SpringPagination.toPagedRequest(Pageable.unpaged())))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of())));

        ResponseEntity<PagedResponse<ListingSummary>> result = controller.listByCategory("stay", Pageable.unpaged());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(catalogService).listByCategory("stay", SpringPagination.toPagedRequest(Pageable.unpaged()));
    }

    @Test
    void listByProvider_embedsEachListingPropertyBlockInOneBatch() {
        // The L31 contract: one batched port call for the page, then each
        // response carries its own property block — the one without stays bare.
        UUID otherId = UUID.randomUUID();
        ProviderListing first = mock(ProviderListing.class);
        ProviderListing second = mock(ProviderListing.class);
        when(catalogService.listByProvider(any(), any())).thenReturn(new PageImpl<>(List.of(first, second)));

        ListingResponse withBlock = response(LISTING_ID);
        ListingResponse bare = response(otherId);
        when(listingMapper.toResponse(first)).thenReturn(withBlock);
        when(listingMapper.toResponse(second)).thenReturn(bare);
        PropertyView property = property(LISTING_ID);
        when(propertyDetailsPort.findByListingIds(any())).thenReturn(Map.of(LISTING_ID, property));

        ResponseEntity<PagedResponse<ListingResponse>> result =
                controller.listByProvider(UUID.randomUUID(), Pageable.unpaged());

        // CodeRabbit round 1 (adopted): the batch lookup must run exactly once,
        // for exactly the page's response IDs — any() would hide a wrong ID set
        // or repeated lookups from this guard.
        verify(propertyDetailsPort, only()).findByListingIds(Set.of(LISTING_ID, otherId));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content())
                .extracting(ListingResponse::property)
                .containsExactly(property, null);
    }

    @Test
    void getById_composesPropertyAndJsonLd_thenCountsTheViewWithTheRemoteAddress() {
        // The CodeRabbit round-1 order: full composition BEFORE the count —
        // and the fingerprint's input is the request's remote address.
        ListingResponse base = response(LISTING_ID);
        when(catalogService.getActiveById(LISTING_ID)).thenReturn(listingView());
        when(listingMapper.toResponse(any(ProviderListingView.class))).thenReturn(base);
        PropertyView property = property(LISTING_ID);
        when(propertyDetailsPort.findByListingId(LISTING_ID)).thenReturn(Optional.of(property));
        RealEstateListingJsonLd jsonLd = jsonLd();
        when(listingSeoService.jsonLdFor(any())).thenReturn(Optional.of(jsonLd));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        ResponseEntity<ListingResponse> result = controller.getById(LISTING_ID, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().property()).isEqualTo(property);
        assertThat(result.getBody().jsonLd()).isEqualTo(jsonLd);
        // CodeRabbit round 2 (adopted): the complete composition happens BEFORE
        // the view is counted — a failed composition must not count (L40). InOrder
        // pins the ordering a bare verify(recordView) could not see.
        InOrder order = inOrder(listingMapper, propertyDetailsPort, listingSeoService, listingViewCounter);
        order.verify(listingMapper).toResponse(any(ProviderListingView.class));
        order.verify(propertyDetailsPort).findByListingId(LISTING_ID);
        order.verify(listingSeoService).jsonLdFor(any());
        order.verify(listingViewCounter).recordView(LISTING_ID, "203.0.113.7");
    }

    @Test
    void getById_withoutPropertyOrJsonLd_stillCountsTheView() {
        ListingResponse base = response(LISTING_ID);
        when(catalogService.getActiveById(LISTING_ID)).thenReturn(listingView());
        when(listingMapper.toResponse(any(ProviderListingView.class))).thenReturn(base);
        when(propertyDetailsPort.findByListingId(LISTING_ID)).thenReturn(Optional.empty());
        when(listingSeoService.jsonLdFor(any())).thenReturn(Optional.empty());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.9");

        ResponseEntity<ListingResponse> result = controller.getById(LISTING_ID, request);

        assertThat(result.getBody().property()).isNull();
        assertThat(result.getBody().jsonLd()).isNull();
        verify(listingViewCounter).recordView(LISTING_ID, "198.51.100.9");
    }

    @Test
    void completeness_composesTheLeafPortAnswers() {
        ProviderListing listing = mock(ProviderListing.class);
        when(catalogService.getOwnedListing(LISTING_ID, null)).thenReturn(listing);
        when(mediaLookupPort.countUploadedByListing(LISTING_ID)).thenReturn(2L);
        PropertyView property = property(LISTING_ID);
        when(propertyDetailsPort.findByListingId(LISTING_ID)).thenReturn(Optional.of(property));

        ResponseEntity<ListingCompletenessResponse> result = controller.completeness(LISTING_ID, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(ListingCompletenessResponse.of(listing, 2L, property));
    }

    @Test
    void create_answers201_withTheProviderResolvedFromTheAuthentication() {
        UUID providerId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        ProviderListingView created = listingView();
        when(catalogService.create(providerId, "عنوان", "وصف", "stay", 35000L, "SAR", 4))
                .thenReturn(created);
        when(listingMapper.toResponse(created)).thenReturn(response(LISTING_ID));
        Authentication authentication = mock(Authentication.class);

        ResponseEntity<ListingResponse> result = controller.create(
                new CatalogController.CreateListingRequest("عنوان", "وصف", "stay", 35000L, "SAR", 4),
                authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(catalogService).create(providerId, "عنوان", "وصف", "stay", 35000L, "SAR", 4);
    }

    @Test
    void update_delegatesTheFullContractShape() {
        Authentication authentication = mock(Authentication.class);
        when(catalogService.update(LISTING_ID, "عنوان", "وصف", "stay", 39000L, "SAR", 6, authentication))
                .thenReturn(mock(ProviderListing.class));
        when(listingMapper.toResponse(any(ProviderListing.class))).thenReturn(response(LISTING_ID));

        ResponseEntity<ListingResponse> result = controller.update(LISTING_ID,
                new CatalogController.UpdateListingRequest("عنوان", "وصف", "stay", 39000L, "SAR", 6),
                authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(catalogService).update(LISTING_ID, "عنوان", "وصف", "stay", 39000L, "SAR", 6, authentication);
    }

    @Test
    void activate_withNoBody_delegatesThePolicyWindowAsNull() {
        Authentication authentication = mock(Authentication.class);
        when(catalogService.activate(LISTING_ID, null, authentication))
                .thenReturn(mock(ProviderListing.class));
        when(listingMapper.toResponse(any(ProviderListing.class))).thenReturn(response(LISTING_ID));

        ResponseEntity<ListingResponse> result = controller.activate(LISTING_ID, null, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(catalogService).activate(LISTING_ID, null, authentication);
    }

    @Test
    void activate_withAnExplicitBody_passesTheWindowThrough() {
        Authentication authentication = mock(Authentication.class);
        Instant expiresAt = Instant.parse("2026-12-31T23:59:59Z");
        when(catalogService.activate(LISTING_ID, expiresAt, authentication))
                .thenReturn(mock(ProviderListing.class));
        when(listingMapper.toResponse(any(ProviderListing.class))).thenReturn(response(LISTING_ID));

        ResponseEntity<ListingResponse> result = controller.activate(LISTING_ID,
                new CatalogController.ActivateListingRequest(expiresAt), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(catalogService).activate(LISTING_ID, expiresAt, authentication);
    }

    @Test
    void renew_pause_archive_delegateToTheService() {
        Authentication authentication = mock(Authentication.class);
        when(catalogService.renew(LISTING_ID, authentication)).thenReturn(mock(ProviderListing.class));
        when(catalogService.pause(LISTING_ID, authentication)).thenReturn(mock(ProviderListing.class));
        when(catalogService.archive(LISTING_ID, authentication)).thenReturn(mock(ProviderListing.class));
        when(listingMapper.toResponse(any(ProviderListing.class))).thenReturn(response(LISTING_ID));

        assertThat(controller.renew(LISTING_ID, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.pause(LISTING_ID, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.archive(LISTING_ID, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(catalogService).renew(LISTING_ID, authentication);
        verify(catalogService).pause(LISTING_ID, authentication);
        verify(catalogService).archive(LISTING_ID, authentication);
    }

    private static ListingResponse response(UUID id) {
        return new ListingResponse(id, "عنوان", "وصف", "stay", BigDecimal.TEN, "SAR", 4,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ProviderListingView listingView() {
        return new ProviderListingView(LISTING_ID, "عنوان", "وصف", "stay", 35000L, "SAR",
                UUID.randomUUID(), "ACTIVE", 4,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PropertyView property(UUID listingId) {
        return new PropertyView(listingId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static RealEstateListingJsonLd jsonLd() {
        return new RealEstateListingJsonLd(RealEstateListingJsonLd.SCHEMA_CONTEXT,
                RealEstateListingJsonLd.SCHEMA_TYPE, "شالية مطلة", null, null, null, null, null);
    }
}
