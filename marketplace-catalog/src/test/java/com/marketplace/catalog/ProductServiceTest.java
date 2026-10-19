package com.marketplace.catalog;

import java.util.Optional;
import java.util.UUID;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A-17 (C.7 — the M1 store root): the registration and owner-read
 * contracts — the dictionary-membership gate (an unknown category code
 * answers the house 404 BEFORE any write, the L31 discipline), the
 * caller-as-owner rule (the owning provider IS the caller — no ownership
 * argument can be forged), and the R5 owner-read posture (a stranger's
 * read answers the honest 404 — existence itself is private).
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private StoreCategoryRepository storeCategoryRepository;
    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private ProductService service;
    private final UUID caller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, storeCategoryRepository, productTypeRepository, currentUserProvider);
    }

    private ProductService.RegisterProductCommand command(String categoryCode) {
        return new ProductService.RegisterProductCommand(
                categoryCode, "Espresso machine, 2-cup", "Pump-driven", 149900L, "SAR");
    }

    private ProductService.RegisterProductCommand typedCommand(String categoryCode, UUID productTypeId) {
        return new ProductService.RegisterProductCommand(
                categoryCode, "Espresso machine, 2-cup", "Pump-driven", 149900L, "SAR", productTypeId);
    }

    @Test
    void registerAnswers404ForUnknownCategoryCode_BeforeAnyWrite() {
        when(storeCategoryRepository.findByCode("home-appliances")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(command("home-appliances"), authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("home-appliances");
    }

    @Test
    void registerSavesWithTheCallerAsOwningProvider() {
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Product product = service.register(command("home-appliances"), authentication);

        assertThat(product.getStoreCategoryCode()).isEqualTo("home-appliances");
        assertThat(product.getProviderId()).as("the owning provider IS the caller").isEqualTo(caller);
        assertThat(product.getPriceMinor()).isEqualTo(149900L);
        assertThat(product.getCurrency()).isEqualTo("SAR");
    }

    @Test
    void registerCanAttachAnExistingProductType() {
        UUID productTypeId = UUID.randomUUID();
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(productTypeRepository.findById(productTypeId)).thenReturn(Optional.of(
                ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product product = service.register(typedCommand("home-appliances", productTypeId), authentication);

        assertThat(product.getProductTypeId()).isEqualTo(productTypeId);
    }

    @Test
    void registerRejectsUnknownProductTypeBeforeProductWrite() {
        UUID unknownType = UUID.randomUUID();
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(productTypeRepository.findById(unknownType)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(typedCommand("home-appliances", unknownType), authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ProductType");
        org.mockito.Mockito.verify(productRepository, org.mockito.Mockito.never()).save(any(Product.class));
    }

    @Test
    void ownerReadReturnsTheProductToStrangerNothing() {
        UUID productId = UUID.randomUUID();
        Product owned = Product.register("home-appliances", "t", null, 1L, "SAR", caller);
        when(productRepository.findById(productId)).thenReturn(Optional.of(owned));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);

        assertThat(service.getOwnedProduct(productId, authentication)).isSameAs(owned);
    }

    @Test
    void strangerReadAnswersTheHonest404() {
        UUID productId = UUID.randomUUID();
        Product owned = Product.register("home-appliances", "t", null, 1L, "SAR", caller);
        when(productRepository.findById(productId)).thenReturn(Optional.of(owned));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.getOwnedProduct(productId, authentication))
                .as("existence itself is private (the R5 posture)")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
