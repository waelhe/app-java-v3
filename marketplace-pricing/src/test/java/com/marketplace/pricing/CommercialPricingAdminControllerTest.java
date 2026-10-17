package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Phase-3 operator surface's controller unit tests — the domain-module
 * house pattern (direct invocation with the service mocked, the
 * {@code OrdersControllerTest} precedent): the surface's contract is the
 * controller's own mapping — status codes, the service arguments, and the
 * response records' shapes (the read-back the study's Phase-1 rule
 * demands). The bean-validation gate and the ADMIN gate are measured on
 * the real chain by the journey IT.
 */
@ExtendWith(MockitoExtension.class)
class CommercialPricingAdminControllerTest {

    @Mock
    private CommercialPricingService service;

    @InjectMocks
    private CommercialPricingAdminController controller;

    // ---------------- price lists ----------------

    @Test
    void createPriceListAnswersCreatedWithTheReadBack() {
        PriceList saved = PriceList.of("Ramadan list", "SAR", true, null, null);
        when(service.createPriceList("Ramadan list", "SAR", true, null, null))
                .thenReturn(saved);

        ResponseEntity<CommercialPricingAdminController.PriceListResponse> result =
                controller.createPriceList(new CommercialPricingAdminController.CreatePriceListRequest(
                        "Ramadan list", "SAR", true, null, null));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().id()).isEqualTo(saved.getId());
        assertThat(result.getBody().name()).isEqualTo("Ramadan list");
        assertThat(result.getBody().currency()).isEqualTo("SAR");
        assertThat(result.getBody().active()).isTrue();
    }

    @Test
    void listPriceListsAnswersTheFamilyRead() {
        PriceList saved = PriceList.of("Ramadan list", "SAR", true, null, null);
        when(service.listPriceLists()).thenReturn(List.of(saved));

        ResponseEntity<List<CommercialPricingAdminController.PriceListResponse>> result =
                controller.listPriceLists();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).currency()).isEqualTo("SAR");
    }

    @Test
    void addPriceListEntryAnswersCreatedWithTheReadBack() {
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        PriceListEntry saved = PriceListEntry.of(listId, productId, variantId, 1200L);
        when(service.addPriceListEntry(listId, productId, variantId, 1200L)).thenReturn(saved);

        ResponseEntity<CommercialPricingAdminController.PriceListEntryResponse> result =
                controller.addPriceListEntry(listId,
                        new CommercialPricingAdminController.CreatePriceListEntryRequest(
                                productId, variantId, 1200L));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().priceListId()).isEqualTo(listId);
        assertThat(result.getBody().variantId()).isEqualTo(variantId);
        assertThat(result.getBody().amountMinor()).isEqualTo(1200L);
    }

    @Test
    void listPriceListEntriesAnswersTheListRead() {
        UUID listId = UUID.randomUUID();
        PriceListEntry saved = PriceListEntry.of(listId, UUID.randomUUID(), null, 1200L);
        when(service.listPriceListEntries(listId)).thenReturn(List.of(saved));

        ResponseEntity<List<CommercialPricingAdminController.PriceListEntryResponse>> result =
                controller.listPriceListEntries(listId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).variantId()).isNull();
    }

    // ---------------- promotions ----------------

    @Test
    void createPromotionAnswersCreatedWithTheDenominatedReadBack() {
        Promotion saved = Promotion.of("Ramadan", "SAR", Promotion.DiscountType.PERCENTAGE,
                1000, 0, null, null, true);
        when(service.createPromotion("Ramadan", "SAR", Promotion.DiscountType.PERCENTAGE,
                1000L, 0L, null, null, true)).thenReturn(saved);

        ResponseEntity<CommercialPricingAdminController.PromotionResponse> result =
                controller.createPromotion(new CommercialPricingAdminController.CreatePromotionRequest(
                        "Ramadan", "SAR", Promotion.DiscountType.PERCENTAGE, 1000L, 0L,
                        null, null, true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().currency()).isEqualTo("SAR");
        assertThat(result.getBody().discountType()).isEqualTo(Promotion.DiscountType.PERCENTAGE);
        assertThat(result.getBody().discountValue()).isEqualTo(1000L);
        assertThat(result.getBody().active()).isTrue();
    }

    @Test
    void listPromotionsAnswersTheFamilyRead() {
        Promotion saved = Promotion.of("Ramadan", "SAR", Promotion.DiscountType.FIXED,
                500L, 0, null, null, true);
        when(service.listPromotions()).thenReturn(List.of(saved));

        ResponseEntity<List<CommercialPricingAdminController.PromotionResponse>> result =
                controller.listPromotions();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).currency()).isEqualTo("SAR");
    }

    // ---------------- vouchers ----------------

    @Test
    void createVoucherAnswersCreatedWithTheDenominatedReadBack() {
        Voucher saved = Voucher.of("RAMADAN-10", "Ramadan", "SAR", Voucher.DiscountType.PERCENTAGE,
                1000L, 0, null, null, 10L, null, true);
        when(service.createVoucher("RAMADAN-10", "Ramadan", "SAR",
                Voucher.DiscountType.PERCENTAGE, 1000L, 0L, null, null, 10L, null, true))
                .thenReturn(saved);

        ResponseEntity<CommercialPricingAdminController.VoucherResponse> result =
                controller.createVoucher(new CommercialPricingAdminController.CreateVoucherRequest(
                        "RAMADAN-10", "Ramadan", "SAR", Voucher.DiscountType.PERCENTAGE,
                        1000L, 0L, null, null, 10L, null, true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("RAMADAN-10");
        assertThat(result.getBody().currency()).isEqualTo("SAR");
        assertThat(result.getBody().maxRedemptions()).isEqualTo(10L);
    }

    @Test
    void listVouchersAnswersTheFamilyRead() {
        Voucher saved = Voucher.of("RAMADAN-10", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 0, null, null, null, null, true);
        when(service.listVouchers()).thenReturn(List.of(saved));

        ResponseEntity<List<CommercialPricingAdminController.VoucherResponse>> result =
                controller.listVouchers();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).currency()).isEqualTo("SAR");
    }

    // ---------------- tax rates ----------------

    @Test
    void createTaxRateAnswersCreatedWithTheReadBack() {
        TaxRate saved = TaxRate.of("SA", "KSA VAT", 1500, TaxRate.TaxMode.INCLUSIVE, true);
        when(service.createTaxRate("SA", "KSA VAT", 1500, TaxRate.TaxMode.INCLUSIVE, true))
                .thenReturn(saved);

        ResponseEntity<CommercialPricingAdminController.TaxRateResponse> result =
                controller.createTaxRate(new CommercialPricingAdminController.CreateTaxRateRequest(
                        "SA", "KSA VAT", 1500, TaxRate.TaxMode.INCLUSIVE, true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().countryCode()).isEqualTo("SA");
        assertThat(result.getBody().rateBps()).isEqualTo(1500);
        assertThat(result.getBody().taxMode()).isEqualTo(TaxRate.TaxMode.INCLUSIVE);
    }

    @Test
    void createTaxRateCarriesTheZeroRateTheDomainAllows() {
        // the controller record must carry 0 bps to the service — a
        // zero-rated jurisdiction is a legal explicit policy (the surface
        // pins the domain's 0..10000 range, never @Positive's 1..N)
        TaxRate saved = TaxRate.of("SA", "Zero-rated", 0, TaxRate.TaxMode.EXCLUSIVE, true);
        when(service.createTaxRate("SA", "Zero-rated", 0, TaxRate.TaxMode.EXCLUSIVE, true))
                .thenReturn(saved);

        ResponseEntity<CommercialPricingAdminController.TaxRateResponse> result =
                controller.createTaxRate(new CommercialPricingAdminController.CreateTaxRateRequest(
                        "SA", "Zero-rated", 0, TaxRate.TaxMode.EXCLUSIVE, true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().rateBps()).isZero();
    }

    @Test
    void listTaxRatesAnswersTheFamilyRead() {
        TaxRate saved = TaxRate.of("SA", "KSA VAT", 1500, TaxRate.TaxMode.INCLUSIVE, true);
        when(service.listTaxRates()).thenReturn(List.of(saved));

        ResponseEntity<List<CommercialPricingAdminController.TaxRateResponse>> result =
                controller.listTaxRates();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).countryCode()).isEqualTo("SA");
    }

    // ---------------- the minimal-surface guard ----------------

    @Test
    void theSurfaceCarriesTheCurrencyThroughEveryDenominatedCommand() {
        // every denominated command must pass the request's currency to the
        // service verbatim — the operator's authored denomination is the
        // policy's own, never a server-side default
        when(service.createPromotion(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any(), any()))
                .thenReturn(Promotion.of("N", "USD", Promotion.DiscountType.FIXED, 100L, 0,
                        null, null, true));
        when(service.createVoucher(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Voucher.of("CODE1", null, "USD", Voucher.DiscountType.FIXED, 100L, 0,
                        null, null, null, null, true));
        when(service.createPriceList(any(), any(), any(), any(), any()))
                .thenReturn(PriceList.of("L", "USD", true, null, null));

        controller.createPromotion(new CommercialPricingAdminController.CreatePromotionRequest(
                "N", "USD", Promotion.DiscountType.FIXED, 100L, 0L, null, null, true));
        verify(service).createPromotion(eq("N"), eq("USD"), eq(Promotion.DiscountType.FIXED),
                eq(100L), eq(0L), isNull(), isNull(), eq(true));

        controller.createVoucher(new CommercialPricingAdminController.CreateVoucherRequest(
                "CODE1", null, "USD", Voucher.DiscountType.FIXED, 100L, 0L, null, null, null,
                null, true));
        verify(service).createVoucher(eq("CODE1"), isNull(), eq("USD"),
                eq(Voucher.DiscountType.FIXED), eq(100L), eq(0L), isNull(), isNull(), isNull(),
                isNull(), eq(true));

        controller.createPriceList(new CommercialPricingAdminController.CreatePriceListRequest(
                "L", "USD", null, null, null));
        verify(service).createPriceList(eq("L"), eq("USD"), isNull(), isNull(), isNull());
    }
}
