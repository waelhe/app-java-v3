package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceGraphQlControllerTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ServiceMapper serviceMapper;

    private ServiceGraphQlController controller;

    private final GraphQlProperties properties = new GraphQlProperties(
            new GraphQlProperties.Errors(false),
            new GraphQlProperties.Pagination(20, 100),
            new GraphQlProperties.Limits(10, 200));

    @BeforeEach
    void setUpController() {
        controller = new ServiceGraphQlController(catalogService, currentUserProvider,
                serviceMapper, properties);
    }

    private static ProviderListingView view(UUID id, String title, String description, Long priceCents, String status) {
        return new ProviderListingView(id, title, description, "general", priceCents, "SAR",
                UUID.randomUUID(), status, null, null, null);
    }

    @Test
    void shouldReturnServiceById() {
        UUID id = UUID.randomUUID();
        ProviderListingView listing = view(id, "Test", "Desc", 5000L, "ACTIVE");
        when(catalogService.getActiveById(id)).thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new ServiceResponse(id, "Test", "Desc", "general", 50.0, "SAR", "ACTIVE"));

        ServiceResponse response = controller.service(id);

        assertThat(response.name()).isEqualTo("Test");
    }

    /**
     * The bounded-collection default: an absent {@code first} resolves to
     * the configured default page size with the deterministic connection
     * order — never the unpaged read this field performed before Phase 1.
     */
    @Test
    void servicesDefaultsToBoundedPageSizeAndDeterministicOrder() {
        ProviderListingView l1 = view(UUID.randomUUID(), "Svc1", "Desc1", 1000L, "ACTIVE");
        ProviderListingView l2 = view(UUID.randomUUID(), "Svc2", "Desc2", 2000L, "ACTIVE");
        when(catalogService.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(l1, l2)));

        controller.services(ScrollSubrange.create(null, null, true));

        verify(catalogService).findAll(argThat((Pageable pageable) ->
                pageable.getPageSize() == 20
                        && pageable.getPageNumber() == 0
                        && "createdAt: DESC,id: DESC".equals(pageable.getSort().toString())));
    }

    /**
     * The hard maximum: a client asking for a page beyond the configured
     * ceiling is clamped — the read stays bounded no matter the request.
     */
    @Test
    void servicesClampsRequestedSizeToTheHardMaximum() {
        when(catalogService.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.services(ScrollSubrange.create(null, 5_000, true));

        verify(catalogService).findAll(argThat((Pageable pageable) ->
                pageable.getPageSize() == 100));
    }

    /**
     * Relay spec: a non-positive {@code first} asks for an empty page —
     * answered honestly, with zero repository interaction.
     */
    @Test
    void servicesAnswersNonPositiveFirstWithAnEmptyPageWithoutReading() {
        var page = controller.services(ScrollSubrange.create(null, 0, true));

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(catalogService);
    }

    /**
     * The cursor round trip: an offset cursor carries the absolute item
     * position; exclusive-after semantics put the next page at
     * {@code (offset + 1) / size} — the page-aligned cursors this surface
     * emits land exactly.
     */
    @Test
    void servicesFollowsAnOffsetCursorToTheExactNextPage() {
        when(catalogService.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        // The terminal cursor of page 0 with size 20 is item offset 19.
        controller.services(ScrollSubrange.create(ScrollPosition.offset(19), 20, true));

        verify(catalogService).findAll(argThat((Pageable pageable) ->
                pageable.getPageNumber() == 1 && pageable.getPageSize() == 20));
    }

    /**
     * Design §6 "validate cursor arguments": a keyset ({@code K_}-shaped)
     * cursor is a foreign format for this field's offset-cursor contract —
     * rejected as a client-contract violation (the resolver's VALIDATION
     * taxonomy), never silently mis-paginated.
     */
    @Test
    void servicesRejectsAKeysetCursorAsAValidationError() {
        ScrollPosition keyset = ScrollPosition.forward(Map.of("createdAt", "2026-01-01"));

        assertThatThrownBy(() -> controller.services(ScrollSubrange.create(keyset, 20, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset cursors");
        verifyNoInteractions(catalogService);
    }

    /**
     * A tampered cursor whose offset overflows the addressable page space
     * is rejected, never wrapped around.
     */
    @Test
    void servicesRejectsAnOffsetBeyondThePaginableRange() {
        assertThatThrownBy(() ->
                controller.services(ScrollSubrange.create(ScrollPosition.offset(Long.MAX_VALUE - 1), 20, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the paginable range");
        verifyNoInteractions(catalogService);
    }

    @Test
    void shouldCreateServiceWhenAdmin() {
        UUID providerId = UUID.randomUUID();
        var auth = new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN");
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        ProviderListingView listing = view(UUID.randomUUID(), "New Svc", "Desc", 3000L, "ACTIVE");
        when(catalogService.create(eq(providerId), eq("New Svc"), eq("Desc"), eq("cat"), eq(3000L), isNull()))
                .thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new ServiceResponse(UUID.randomUUID(), "New Svc", "Desc", "general", 30.0, "SAR", "ACTIVE"));

        var input = new ServiceInput("New Svc", "Desc", "cat", 3000L);
        ServiceResponse response = controller.createService(input, auth);

        assertThat(response.name()).isEqualTo("New Svc");
    }
}
