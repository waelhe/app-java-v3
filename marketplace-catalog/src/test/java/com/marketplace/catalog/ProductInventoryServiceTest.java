package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
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

    private Product product() {
        return Product.register("journey-appliances", "Espresso", null, 149900L, "SAR", seller);
    }

    @Test
    void theOwningProviderWritesTheShelf() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product saved = service.setStock(product.getId(), 12, authentication);

        assertThat(saved.getStockQuantity()).isEqualTo(12);
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
        Product product = product();
        product.restock(3);
        // One placement reserved 2 — the shelf cannot drop below it.
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(productRepository.reserve(product.getId(), 2)).thenReturn(1);

        // simulate the reservation the port writes (the service never
        // read-modify-writes the reserved set; the test pins the entity guard)
        product.restock(1);

        assertThatThrownBy(() -> service.setStock(product.getId(), 1, authentication))
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
        com.marketplace.catalog.spi.ProductStockAdapter adapter =
                new com.marketplace.catalog.spi.ProductStockAdapter(repository);
        UUID id = UUID.randomUUID();
        when(repository.reserve(id, 2)).thenReturn(0);

        assertThatThrownBy(() -> adapter.reserve(java.util.Map.of(id, 2)))
                .isInstanceOf(ConflictException.class);
    }
}
