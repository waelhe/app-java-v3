package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 6 (ADR-0002) — the store shelf's unit gate: the ownership gate
 * (the listing flow's exact shape: the product's provider or ADMIN, an
 * honest 404 otherwise), the stock guard (the shelf never falls below the
 * reserved set), and the lifecycle machine (SUSPENDED is a toggle, ARCHIVED
 * is terminal).
 *
 * <p><b>The fixture discipline (the unified entity, the house Instancio
 * pattern):</b> the Product IS the one stock/reserved/lifecycle record, so
 * every fixture pins its state through Instancio (the CatalogServiceTest
 * house pattern — the protected JPA constructor, the getters as the
 * type-safe selectors). The reserved set is the {@code ProductStockPort}'s
 * own write — a conditional update at the database the entity never
 * read-modify-writes — so the fixture materializes it directly instead of
 * simulating it through {@code restock}: {@code restock} is the provider's
 * ABSOLUTE shelf write ({@code stockQuantity = quantity}, refused below the
 * reserved set) and carries no reservation semantics whatsoever.
 */
@ExtendWith(MockitoExtension.class)
class ProductInventoryServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private ProductInventoryService service;

    private final UUID seller = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductInventoryService(productRepository, currentUserProvider);
    }

    /**
     * The unified entity — the provider's own live shelf, state pinned to
     * the register-honest fresh shape (zero on hand, nothing reserved):
     * Instancio generates the unpinned numerics, and a random reserved set
     * would trip the shelf's own guard on the absolute write (measured:
     * reserved=3000 refused a 12-unit write).
     */
    private Product product() {
        return Instancio.of(Product.class)
                .set(field(Product::getProviderId), seller)
                .set(field(Product::getStatus), ProductStatus.ACTIVE)
                .set(field(Product::getStockQuantity), 0)
                .set(field(Product::getReservedQuantity), 0)
                .create();
    }

    /** The shelf as the port materialized it: units on hand + the reserved set. */
    private Product productWithShelfState(int stockQuantity, int reservedQuantity) {
        return Instancio.of(Product.class)
                .set(field(Product::getProviderId), seller)
                .set(field(Product::getStatus), ProductStatus.ACTIVE)
                .set(field(Product::getStockQuantity), stockQuantity)
                .set(field(Product::getReservedQuantity), reservedQuantity)
                .create();
    }

    @Test
    void theOwningProviderWritesTheShelf() {
        Product product = product();
        int shelfBefore = product.getStockQuantity();
        int reservedBefore = product.getReservedQuantity();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product saved = service.setStock(product.getId(), 12, authentication);

        // The absolute write lands verbatim — the restock semantics (an
        // authoritative shelf count, not an increment). The ownership gate
        // short-circuits on the owning provider (isAdmin never asked), so
        // no isAdmin stub exists here — the strict-stubs contract.
        assertThat(saved.getStockQuantity())
                .as("the absolute write (was shelf=%d reserved=%d)", shelfBefore, reservedBefore)
                .isEqualTo(12);
    }

    @Test
    void aStrangerGetsTheHonest404OnTheWriteSurface() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(stranger);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.setStock(product.getId(), 5, authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).save(any());
    }

    @Test
    void theShelfNeverFallsBelowTheReservedSet() {
        // One placement reserved 2 — the port's conditional update wrote it
        // (the entity never read-modify-writes the reserved set), so the
        // fixture materializes exactly that state: 3 on the shelf, 2
        // reserved. Lowering the shelf to 1 strands the placement — the
        // entity's own guard refuses it. The service never reserves here,
        // so no reserve stub exists — the strict-stubs contract.
        Product product = productWithShelfState(3, 2);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);

        assertThatThrownBy(() -> service.setStock(product.getId(), 1, authentication))
                .as("shelf=%d reserved=%d requested=%d",
                        product.getStockQuantity(), product.getReservedQuantity(), 1)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLifecycleToggleAndItsTerminalEdge() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.suspend(product.getId(), authentication).getStatus()).isEqualTo(ProductStatus.SUSPENDED);
        assertThat(service.reactivate(product.getId(), authentication).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(service.archive(product.getId(), authentication).getStatus()).isEqualTo(ProductStatus.ARCHIVED);

        // ARCHIVED is terminal — no transition out, from the service either.
        assertThatThrownBy(() -> service.reactivate(product.getId(), authentication))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theStockAdapterRefusesTheWholeMapOnTheFirstShortRow() {
        ProductRepository repository = org.mockito.Mockito.mock(ProductRepository.class);
        ProductVariantRepository variantRepository = org.mockito.Mockito.mock(ProductVariantRepository.class);
        com.marketplace.catalog.spi.ProductStockAdapter adapter =
                new com.marketplace.catalog.spi.ProductStockAdapter(repository, variantRepository);
        UUID id = UUID.randomUUID();
        when(repository.reserve(id, 2)).thenReturn(0);

        assertThatThrownBy(() -> adapter.reserve(java.util.Map.of(id, 2)))
                .isInstanceOf(ConflictException.class);
    }
}
