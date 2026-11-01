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
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.CursorEncoder;
import org.springframework.graphql.data.pagination.CursorStrategy;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.graphql.data.query.ScrollPositionCursorStrategy;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
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
            new GraphQlProperties.Limits(10, 200),
            new GraphQlProperties.Bulk(200));

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
     * The default size remains configuration-owned, and the catalog receives
     * the initial keyset position plus a bounded Spring Data Limit.
     */
    @Test
    void servicesDefaultsToBoundedWindowAndInitialKeysetPosition() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenReturn(Window.from(List.of(), index -> ScrollPosition.keyset(), false));

        var window = fetcher.services(null, null);

        assertThat(window.getContent()).isEmpty();
        verify(catalogService).scrollActiveListings(
                argThat(KeysetScrollPosition::isInitial), eq(Limit.of(20)));
    }

    /**
     * The hard maximum: a client asking for a larger window is clamped before
     * the catalog SPI is called.
     */
    @Test
    void servicesClampsRequestedSizeToTheHardMaximum() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenReturn(Window.from(List.of(), index -> ScrollPosition.keyset(), false));

        fetcher.services(5_000, null);

        verify(catalogService).scrollActiveListings(any(KeysetScrollPosition.class), eq(Limit.of(100)));
    }

    @Test
    void servicesAnswersNonPositiveFirstWithAnEmptyWindowWithoutReading() {
        var window = fetcher.services(0, null);

        assertThat(window.getContent()).isEmpty();
        assertThat(window.hasNext()).isFalse();
        verifyNoInteractions(catalogService);
    }

    /**
     * A cursor from the previous page resumes after its exact keyset, even
     * when clients change the requested window size.
     */
    @Test
    void servicesFollowsAnOpaqueKeysetCursorWithoutAnOffsetPageCalculation() {
        UUID lastId = UUID.randomUUID();
        KeysetScrollPosition after = ScrollPosition.forward(Map.of(
                "createdAt", Instant.parse("2026-01-01T00:00:00Z"), "id", lastId));
        String cursor = cursorStrategy.toCursor(after);
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenReturn(Window.from(List.of(), index -> ScrollPosition.keyset(), false));

        fetcher.services(7, cursor);

        verify(catalogService).scrollActiveListings(
                argThat(position -> !position.isInitial()
                        && position.scrollsForward()
                        && position.getKeys().get("id").equals(lastId)),
                eq(Limit.of(7)));
    }

    /**
     * A keyset-shaped cursor with a different sort-key set is not accepted as
     * a cursor emitted by this connection.
     */
    @Test
    void servicesRejectsAForeignKeysetCursorAsAValidationError() {
        String foreignCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("wrongKey", "value")));

        assertThatThrownBy(() -> fetcher.services(20, foreignCursor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset cursor emitted by services");
        verifyNoInteractions(catalogService);
    }

    @Test
    void servicesRejectsAnUndecodableCursorAsAValidationError() {
        assertThatThrownBy(() -> fetcher.services(20, "not-a-cursor-at-all"))
                .isInstanceOf(IllegalArgumentException.class);
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
