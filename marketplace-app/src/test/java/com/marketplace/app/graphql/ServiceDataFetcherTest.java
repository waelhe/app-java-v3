package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Service;
import com.marketplace.app.graphql.generated.types.ServiceStatus;
import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.CursorEncoder;
import org.springframework.graphql.data.pagination.CursorStrategy;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.graphql.data.query.ScrollPositionCursorStrategy;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

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
class ServiceDataFetcherTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ServiceMapper serviceMapper;

    private ServiceDataFetcher fetcher;

    private final GraphQlProperties properties = new GraphQlProperties(
            new GraphQlProperties.Errors(false),
            new GraphQlProperties.Pagination(20, 100),
            new GraphQlProperties.Limits(10, 200));

    /**
     * The connection-cursor codec under test — the exact construction the
     * Boot auto-configuration performs ({@code GraphQlDataAutoConfiguration#
     * cursorStrategy}: {@code CursorStrategy.withEncoder(new
     * ScrollPositionCursorStrategy(), CursorEncoder.base64())}), so the
     * unit-level cursor assertions exercise the same wire format the
     * integration tests drive over HTTP.
     */
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy =
            CursorStrategy.withEncoder(new ScrollPositionCursorStrategy(), CursorEncoder.base64());

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * Instantiates the data fetcher under test with the mocked SPI and
     * principal provider plus the real mapper, the production-shaped
     * properties (20/100 pagination, 10/200 budgets), the framework's own
     * cursor codec, and the default Jakarta validator.
     */
    @BeforeEach
    void setUpFetcher() {
        fetcher = new ServiceDataFetcher(catalogService, currentUserProvider,
                serviceMapper, properties, cursorStrategy, validator);
    }

    /** The mutation path reads the principal from the (test-populated) security context. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** A minimal shared view for the SPI's stubbed answers. */
    private static ProviderListingView view(UUID id, String title, String description, Long priceCents, String status) {
        return new ProviderListingView(id, title, description, "general", priceCents, "SAR",
                UUID.randomUUID(), status, null, null, null);
    }

    /**
     * service(id) delegates to the SPI's active-by-id lookup and maps the
     * shared view into the response contract.
     */
    @Test
    void shouldReturnServiceById() {
        UUID id = UUID.randomUUID();
        ProviderListingView listing = view(id, "Test", "Desc", 5000L, "ACTIVE");
        when(catalogService.getActiveById(id)).thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new Service(id.toString(), "Test", "Desc", "general", 50.0, "SAR", ServiceStatus.ACTIVE));

        Service response = fetcher.service(id);

        assertThat(response.getName()).isEqualTo("Test");
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

        fetcher.services(null, null);

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

        fetcher.services(5_000, null);

        verify(catalogService).findAll(argThat((Pageable pageable) ->
                pageable.getPageSize() == 100));
    }

    /**
     * Relay spec: a non-positive {@code first} asks for an empty page —
     * answered honestly, with zero repository interaction.
     */
    @Test
    void servicesAnswersNonPositiveFirstWithAnEmptyPageWithoutReading() {
        var page = fetcher.services(0, null);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(catalogService);
    }

    /**
     * The cursor round trip: an offset cursor carries the absolute item
     * position; exclusive-after semantics put the next page at
     * {@code (offset + 1) / size} — the page-aligned cursors this surface
     * emits land exactly. The cursor string is produced by the same
     * framework codec the runtime injects.
     */
    @Test
    void servicesFollowsAnOffsetCursorToTheExactNextPage() {
        when(catalogService.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        // The terminal cursor of page 0 with size 20 is item offset 19.
        String endCursor = cursorStrategy.toCursor(ScrollPosition.offset(19));
        fetcher.services(20, endCursor);

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
        String keysetCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("createdAt", "2026-01-01")));

        assertThatThrownBy(() -> fetcher.services(20, keysetCursor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset cursors");
        verifyNoInteractions(catalogService);
    }

    /**
     * An undecodable cursor string is rejected by the framework codec
     * itself (IllegalArgumentException from the base64/prefix decoding —
     * the same exception family the resolver maps to VAL-001) — the
     * unit-level twin of the garbage-cursor integration leg.
     */
    @Test
    void servicesRejectsAnUndecodableCursorAsAValidationError() {
        assertThatThrownBy(() -> fetcher.services(20, "not-a-cursor-at-all"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(catalogService);
    }

    /**
     * A tampered cursor whose offset overflows the addressable page space
     * is rejected, never wrapped around.
     */
    @Test
    void servicesRejectsAnOffsetBeyondThePaginableRange() {
        String tampered = cursorStrategy.toCursor(ScrollPosition.offset(Long.MAX_VALUE - 1));

        assertThatThrownBy(() -> fetcher.services(20, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the paginable range");
        verifyNoInteractions(catalogService);
    }

    /**
     * createService resolves the authenticated caller from the security
     * context into the owning provider id and delegates the write to the
     * SPI's create use case — the same authoritative business rule the
     * REST surface invokes.
     */
    @Test
    void shouldCreateServiceWhenAdmin() {
        UUID providerId = UUID.randomUUID();
        var auth = new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        ProviderListingView listing = view(UUID.randomUUID(), "New Svc", "Desc", 3000L, "ACTIVE");
        when(catalogService.create(eq(providerId), eq("New Svc"), eq("Desc"), eq("cat"), eq(3000L), isNull()))
                .thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new Service(UUID.randomUUID().toString(), "New Svc", "Desc", "general", 30.0, "SAR", ServiceStatus.ACTIVE));

        var input = new ServiceInput("New Svc", "Desc", "cat", 3000L);
        Service response = fetcher.createService(input);

        assertThat(response.getName()).isEqualTo("New Svc");
    }

    /**
     * The boundary Bean-Validation contract the DGS path enforces
     * explicitly: a negative price violates the record's {@code @Min(0)}
     * and surfaces as {@link ConstraintViolationException} — the same
     * exception type (and therefore the same VAL-001/validation mapping)
     * the Spring {@code @Valid} path produced before the migration. The
     * write never reaches the SPI.
     */
    @Test
    void createServiceRejectsANegativePriceAtTheBoundary() {
        var input = new ServiceInput("Bad Price", "Negative price", "cat", -1L);

        assertThatThrownBy(() -> fetcher.createService(input))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(catalogService);
    }
}
