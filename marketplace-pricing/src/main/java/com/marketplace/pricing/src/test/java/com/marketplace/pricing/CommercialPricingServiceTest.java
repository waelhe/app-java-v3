package com.marketplace.pricing;

import com.marketplace.shared.api.CommercialPricingPort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProductPricingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Saleor fit-gap study's Phase 3 engine tests — the commercial rules
 * as EXPLICIT policies: the price-list override precedence (the exact
 * variant entry first, then the product-level fallback), the promotion
 * selection (best single applicable of the order's currency — no
 * stacking), the voucher validation and locked settlement (idempotency +
 * limits + the denomination refusal), and the tax modes (INCLUSIVE
 * extracts / EXCLUSIVE adds; a zero-rated jurisdiction is a legal
 * policy). Each test pins one rule of the documented resolution law.
 */
@ExtendWith(MockitoExtension.class)
class CommercialPricingServiceTest {

    @Mock
    private PriceListRepository priceLists;
    @Mock
    private PriceListEntryRepository priceListEntries;
    @Mock
    private PromotionRepository promotions;
    @Mock
    private VoucherRepository vouchers;
    @Mock
    private VoucherRedemptionRepository redemptions;
    @Mock
    private TaxRateRepository taxRates;
    @Mock
    private ProductPricingPort productPricingPort;

    private CommercialPricingService service;

    private final UUID consumer = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CommercialPricingService(priceLists, priceListEntries, promotions,
                vouchers, redemptions, taxRates, productPricingPort,
                new CommercialPricingProperties("SA"));
        // no tax policy by default — each tax test pins its own
        lenient().when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.empty());
    }

    private void catalogPrice(long priceMinor) {
        when(productPricingPort.priceOf(product)).thenReturn(
                new ProductPricingPort.ProductPrice(product, seller, priceMinor, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
    }

    private void activeList(PriceList list) {
        when(priceLists.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(list));
    }

    // ------------------------------------------------------------------
    // Unit-price resolution: the price-list layer over the catalog's own
    // ------------------------------------------------------------------

    @Test
    void thePriceListEntryOverridesTheCatalogsOwnPrice() {
        catalogPrice(1500L);
        PriceList list = PriceList.of("Ramadan list", "SAR", true, null, null);
        activeList(list);
        when(priceListEntries.findByProductIdAndPriceListIdIn(product, List.of(list.getId())))
                .thenReturn(List.of(PriceListEntry.of(list.getId(), product, null, 1200L)));

        CommercialPricingPort.ResolvedUnitPrice resolved =
                service.resolveUnitPrice(product, null);

        assertThat(resolved.priceMinor()).isEqualTo(1200L);
        assertThat(resolved.priceListId()).isEqualTo(list.getId());
        assertThat(resolved.purchasable()).isTrue();
        assertThat(resolved.providerId()).isEqualTo(seller);
    }

    @Test
    void theVariantLineFallsBackToTheProductLevelEntry() {
        UUID variant = UUID.randomUUID();
        when(productPricingPort.priceOfVariant(variant)).thenReturn(
                new ProductPricingPort.VariantPrice(variant, product, seller, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.ACTIVE));
        PriceList list = PriceList.of("Ramadan list", "SAR", true, null, null);
        activeList(list);
        // only the PRODUCT-level entry exists — the variant line still reads it
        when(priceListEntries.findByProductIdAndPriceListIdIn(product, List.of(list.getId())))
                .thenReturn(List.of(PriceListEntry.of(list.getId(), product, null, 1200L)));

        CommercialPricingPort.ResolvedUnitPrice resolved =
                service.resolveUnitPrice(product, variant);

        assertThat(resolved.priceMinor()).isEqualTo(1200L);
        assertThat(resolved.priceListId()).isEqualTo(list.getId());
    }

    @Test
    void theExactVariantEntryOutranksTheProductLevelFallback() {
        UUID variant = UUID.randomUUID();
        when(productPricingPort.priceOfVariant(variant)).thenReturn(
                new ProductPricingPort.VariantPrice(variant, product, seller, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.ACTIVE));
        PriceList firstList = PriceList.of("First list", "SAR", true, null, null);
        PriceList secondList = PriceList.of("Second list", "SAR", true, null, null);
        when(priceLists.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(firstList, secondList));
        // the FIRST list carries only the product-level entry; the SECOND
        // list carries the variant's own exact entry — the port's documented
        // precedence: exactness outranks list order.
        when(priceListEntries.findByProductIdAndPriceListIdIn(product,
                List.of(firstList.getId(), secondList.getId())))
                .thenReturn(List.of(
                        PriceListEntry.of(firstList.getId(), product, null, 1100L),
                        PriceListEntry.of(secondList.getId(), product, variant, 1300L)));

        CommercialPricingPort.ResolvedUnitPrice resolved =
                service.resolveUnitPrice(product, variant);

        assertThat(resolved.priceMinor()).isEqualTo(1300L);
        assertThat(resolved.priceListId()).isEqualTo(secondList.getId());
    }

    @Test
    void aSuspendedProductHidesItsPriceListToo() {
        when(productPricingPort.priceOf(product)).thenReturn(
                new ProductPricingPort.ProductPrice(product, seller, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.SUSPENDED));

        CommercialPricingPort.ResolvedUnitPrice resolved =
                service.resolveUnitPrice(product, null);

        // Saleor's publication rule: the override never rescues a hidden
        // shelf — the refusal names the shelf
        assertThat(resolved.purchasable()).isFalse();
        assertThat(resolved.refusal()).contains("SUSPENDED");
        verify(priceLists, never()).findActiveCovering(any(), any());
    }

    @Test
    void noMatchingEntryKeepsTheCatalogsOwnPrice() {
        catalogPrice(1500L);
        PriceList list = PriceList.of("Other products' list", "SAR", true, null, null);
        activeList(list);
        when(priceListEntries.findByProductIdAndPriceListIdIn(product, List.of(list.getId())))
                .thenReturn(List.of());

        assertThat(service.resolveUnitPrice(product, null).priceMinor()).isEqualTo(1500L);
    }

    // ------------------------------------------------------------------
    // The breakdown: promotion → voucher → tax (the stacking law)
    // ------------------------------------------------------------------

    @Test
    void theBestSingleApplicablePromotionAppliesAndPromotionsNeverStack() {
        when(promotions.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(
                        Promotion.of("Small 5%", "SAR", Promotion.DiscountType.PERCENTAGE, 500, 0,
                                null, null, true),
                        Promotion.of("Big 10%", "SAR", Promotion.DiscountType.PERCENTAGE, 1000, 0,
                                null, null, true)));
        // the voucher is absent; no tax policy

        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 10000L, "SAR", null);

        // the BEST single promotion (10% = 1000) — the 5% one never stacks
        assertThat(breakdown.promotionDiscountMinor()).isEqualTo(1000L);
        assertThat(breakdown.promotionName()).isEqualTo("Big 10%");
        assertThat(breakdown.voucherDiscountMinor()).isZero();
        assertThat(breakdown.totalMinor()).isEqualTo(9000L);
    }

    @Test
    void thePromotionSelectionReadsTheOrdersCurrencyOnly() {
        // the repository IS the currency scope — the service must pass the
        // order's single currency through, never a policy default
        service.computeBreakdown(consumer, 10000L, "USD", null);

        verify(promotions).findActiveCovering(org.mockito.ArgumentMatchers.eq("USD"),
                any(Instant.class));
    }

    @Test
    void aBelowMinimumSpendPromotionDoesNotApply() {
        when(promotions.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(Promotion.of("Big spenders", "SAR", Promotion.DiscountType.FIXED,
                        2000L, 20000L, null, null, true)));

        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 10000L, "SAR", null);

        assertThat(breakdown.promotionDiscountMinor()).isZero();
        assertThat(breakdown.totalMinor()).isEqualTo(10000L);
    }

    @Test
    void aFixedDiscountCapsAtTheSubtotal() {
        when(promotions.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(Promotion.of("Everything free", "SAR", Promotion.DiscountType.FIXED,
                        999999L, 0, null, null, true)));

        assertThat(service.computeBreakdown(consumer, 100L, "SAR", null)
                .promotionDiscountMinor()).isEqualTo(100L);
    }

    @Test
    void theVoucherEvaluatesOnThePostPromotionSubtotal() {
        when(promotions.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(Promotion.of("10%", "SAR", Promotion.DiscountType.PERCENTAGE,
                        1000, 0, null, null, true)));
        Voucher voucher = Voucher.of("SAVE500", "Save 500", "SAR", Voucher.DiscountType.FIXED,
                500L, 2000L, null, null, null, null, true);
        when(vouchers.findByCode("SAVE500")).thenReturn(Optional.of(voucher));

        // subtotal 10000 → promotion 1000 → 9000; the voucher's 500 hits
        // the post-promotion 9000
        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 10000L, "SAR", "SAVE500");

        assertThat(breakdown.promotionDiscountMinor()).isEqualTo(1000L);
        assertThat(breakdown.voucherDiscountMinor()).isEqualTo(500L);
        assertThat(breakdown.totalMinor()).isEqualTo(8500L);
    }

    @Test
    void aVoucherOfAnotherCurrencyIsRefusedWithTheHonest409() {
        // a USD-authored voucher must never discount a SAR order — its
        // amount and minimum spend are authored for their own denomination
        Voucher voucher = Voucher.of("USDONLY", null, "USD", Voucher.DiscountType.FIXED,
                500L, 0, null, null, null, null, true);
        when(vouchers.findByCode("USDONLY")).thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.computeBreakdown(consumer, 10000L, "SAR", "USDONLY"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("denominated in USD")
                .hasMessageContaining("order is in SAR");
    }

    @Test
    void anUnrecognizedVoucherCodeAnswersTheHonest409() {
        when(vouchers.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.computeBreakdown(consumer, 10000L, "SAR", "NOPE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not recognized");
    }

    @Test
    void aBelowMinimumSpendVoucherAnswersTheHonest409() {
        Voucher voucher = Voucher.of("BIGSPENDER", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 50000L, null, null, null, null, true);
        when(vouchers.findByCode("BIGSPENDER")).thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.computeBreakdown(consumer, 10000L, "SAR", "BIGSPENDER"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("minimum spend");
    }

    @Test
    void anExhaustedVoucherAnswersTheHonest409() {
        Voucher voucher = Voucher.of("ONCE", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 0, null, null, 1L, null, true);
        when(vouchers.findByCode("ONCE")).thenReturn(Optional.of(voucher));
        when(redemptions.countByVoucherId(voucher.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.computeBreakdown(consumer, 10000L, "SAR", "ONCE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("redemption limit");
    }

    @Test
    void anExhaustedPerUserVoucherLimitAnswersTheHonest409() {
        Voucher voucher = Voucher.of("ONCEPERUSER", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 0, null, null, null, 1L, true);
        when(vouchers.findByCode("ONCEPERUSER")).thenReturn(Optional.of(voucher));
        when(redemptions.countByVoucherIdAndConsumerId(voucher.getId(), consumer)).thenReturn(1L);

        assertThatThrownBy(() -> service.computeBreakdown(consumer, 10000L, "SAR", "ONCEPERUSER"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("per-user redemption limit");
    }

    // ------------------------------------------------------------------
    // Tax: the jurisdiction's explicit policy (frozen at placement)
    // ------------------------------------------------------------------

    @Test
    void anInclusiveTaxExtractsTheComponentWithoutChangingTheTotal() {
        when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.of(
                TaxRate.of("SA", "KSA VAT", 1500, TaxRate.TaxMode.INCLUSIVE, true)));

        // 11500 gross at 15% inclusive → the 1500 contained component
        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 11500L, "SAR", null);

        assertThat(breakdown.taxMinor()).isEqualTo(1500L);
        assertThat(breakdown.taxInclusive()).isTrue();
        assertThat(breakdown.totalMinor()).isEqualTo(11500L);
    }

    @Test
    void anExclusiveTaxAddsTheComponentOnTop() {
        when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.of(
                TaxRate.of("SA", "KSA VAT", 1500, TaxRate.TaxMode.EXCLUSIVE, true)));

        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 10000L, "SAR", null);

        assertThat(breakdown.taxMinor()).isEqualTo(1500L);
        assertThat(breakdown.taxInclusive()).isFalse();
        assertThat(breakdown.totalMinor()).isEqualTo(11500L);
    }

    @Test
    void aZeroRatedJurisdictionIsALegalExplicitPolicy() {
        // 0 bps is inside the domain (0..10000) — the surface must accept it,
        // never answer a validation 400 for a zero rate
        when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.of(
                TaxRate.of("SA", "Zero-rated", 0, TaxRate.TaxMode.EXCLUSIVE, true)));

        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 10000L, "SAR", null);

        assertThat(breakdown.taxMinor()).isZero();
        assertThat(breakdown.taxRateBps()).isZero();
        assertThat(breakdown.totalMinor()).isEqualTo(10000L);
    }

    @Test
    void theTaxComputesOnTheDiscountedSubtotal() {
        when(promotions.findActiveCovering(org.mockito.ArgumentMatchers.eq("SAR"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(Promotion.of("10%", "SAR", Promotion.DiscountType.PERCENTAGE,
                        1000, 0, null, null, true)));
        when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.of(
                TaxRate.of("SA", "KSA VAT", 1500, TaxRate.TaxMode.EXCLUSIVE, true)));

        // 10000 − 1000 = 9000 discounted; the 15% exclusive tax = 1350
        CommercialPricingPort.CommercialBreakdown breakdown =
                service.computeBreakdown(consumer, 10000L, "SAR", null);

        assertThat(breakdown.taxMinor()).isEqualTo(1350L);
        assertThat(breakdown.totalMinor()).isEqualTo(10350L);
    }

    // ------------------------------------------------------------------
    // The settlement: the locked WRITE inside the placement
    // ------------------------------------------------------------------

    @Test
    void settleVoucherRecordsTheRedemptionUnderTheRowsLock() {
        Voucher voucher = Voucher.of("SAVE500", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 0, null, null, 10L, null, true);
        when(vouchers.findByCodeForUpdate("SAVE500")).thenReturn(Optional.of(voucher));
        when(redemptions.findByVoucherIdAndOrderId(org.mockito.ArgumentMatchers.eq(voucher.getId()), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(redemptions.countByVoucherId(voucher.getId())).thenReturn(3L);
        UUID order = UUID.randomUUID();

        service.settleVoucher(order, consumer, "SAVE500", 500L, "SAR");

        verify(redemptions).save(any(VoucherRedemption.class));
    }

    @Test
    void settleVoucherRefusesAMismatchedDenomination() {
        Voucher voucher = Voucher.of("USDONLY", null, "USD", Voucher.DiscountType.FIXED,
                500L, 0, null, null, 10L, null, true);
        when(vouchers.findByCodeForUpdate("USDONLY")).thenReturn(Optional.of(voucher));
        when(redemptions.findByVoucherIdAndOrderId(org.mockito.ArgumentMatchers.eq(voucher.getId()), any(UUID.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleVoucher(UUID.randomUUID(), consumer, "USDONLY",
                500L, "SAR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("denominated in USD");
    }

    @Test
    void aReplayedSettleForTheSameOrderIsALoggedNoOp() {
        Voucher voucher = Voucher.of("SAVE500", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 0, null, null, 10L, null, true);
        when(vouchers.findByCodeForUpdate("SAVE500")).thenReturn(Optional.of(voucher));
        UUID order = UUID.randomUUID();
        when(redemptions.findByVoucherIdAndOrderId(voucher.getId(), order))
                .thenReturn(Optional.of(VoucherRedemption.of(voucher.getId(), order, consumer,
                        500L, "SAR", Instant.now())));

        service.settleVoucher(order, consumer, "SAVE500", 500L, "SAR");

        verify(redemptions, never()).save(any(VoucherRedemption.class));
    }

    @Test
    void aSettlePastTheGlobalLimitRefusesAndRollsThePlacementBack() {
        Voucher voucher = Voucher.of("ONCE", null, "SAR", Voucher.DiscountType.FIXED,
                500L, 0, null, null, 1L, null, true);
        when(vouchers.findByCodeForUpdate("ONCE")).thenReturn(Optional.of(voucher));
        when(redemptions.findByVoucherIdAndOrderId(org.mockito.ArgumentMatchers.eq(voucher.getId()), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(redemptions.countByVoucherId(voucher.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.settleVoucher(UUID.randomUUID(), consumer, "ONCE",
                500L, "SAR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("redemption limit");
    }

    // ------------------------------------------------------------------
    // The policy management surface
    // ------------------------------------------------------------------

    @Test
    void activatingANewCountryRateSupersedesTheOldOne() {
        TaxRate old = TaxRate.of("SA", "Old VAT", 500, TaxRate.TaxMode.INCLUSIVE, true);
        when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.of(old));
        when(taxRates.save(any(TaxRate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taxRates.saveAndFlush(any(TaxRate.class))).thenAnswer(inv -> inv.getArgument(0));

        TaxRate created = service.createTaxRate("SA", "New VAT", 1500,
                TaxRate.TaxMode.INCLUSIVE, true);

        assertThat(created.isActive()).isTrue();
        assertThat(created.getRateBps()).isEqualTo(1500);
        // history is never edited — the old row is DEACTIVATED, not mutated,
        // and the deactivation UPDATE is FLUSHED before the new row's INSERT
        // (Hibernate's ActionQueue runs inserts first — the flush order the
        // partial unique index demands)
        assertThat(old.isActive()).isFalse();
        InOrder order = inOrder(taxRates);
        order.verify(taxRates).saveAndFlush(old);
        order.verify(taxRates).save(created);
    }

    @Test
    void theSupersedingLookupRidesTheNormalizedCountryCode() {
        // "sa" must find the "SA" row — TaxRate.of normalizes, the lookup
        // rides the normalized form, never the raw input
        TaxRate old = TaxRate.of("SA", "Old VAT", 500, TaxRate.TaxMode.INCLUSIVE, true);
        when(taxRates.findByCountryCodeAndActiveTrue("SA")).thenReturn(Optional.of(old));
        when(taxRates.save(any(TaxRate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taxRates.saveAndFlush(any(TaxRate.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createTaxRate("sa", "New VAT", 1500, TaxRate.TaxMode.INCLUSIVE, true);

        verify(taxRates).findByCountryCodeAndActiveTrue("SA");
        assertThat(old.isActive()).isFalse();
    }

    @Test
    void voucherCodesAreNormalizedToUpperTrimmed() {
        when(vouchers.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        Voucher created = service.createVoucher("  Ramadan-10  ", "Ramadan", "sar",
                Voucher.DiscountType.PERCENTAGE, 1000L, 0L, null, null, null, null, true);

        assertThat(created.getCode()).isEqualTo("RAMADAN-10");
        assertThat(created.getCurrency()).isEqualTo("SAR");
    }

    @Test
    void promotionsNormalizeTheirCurrency() {
        when(promotions.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));

        Promotion created = service.createPromotion("Ramadan", "sar",
                Promotion.DiscountType.PERCENTAGE, 1000L, 0L, null, null, true);

        assertThat(created.getCurrency()).isEqualTo("SAR");
    }
}
