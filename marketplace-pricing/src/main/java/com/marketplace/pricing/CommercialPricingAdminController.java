package com.marketplace.pricing;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marketplace.shared.api.ApiConstants;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3 operator surface: the commercial
 * policies — price lists, promotions, vouchers, tax rates — are managed
 * by the platform's ADMIN through owned commands with read-back (the
 * study's Phase 1 rule: «each management command provides a stable error
 * contract and a way for the UI to verify the final state»). Every write
 * answers the created row; every family answers its list — the operator's
 * verification is the response itself, never a silent accept.
 *
 * <p>Promotions and vouchers are DENOMINATED: the request carries the
 * policy's ISO-4217 currency, and the resolution never applies a policy
 * to an order of another currency. The tax-rate surface accepts the
 * domain's full 0..10000 basis-point range — a zero-rated jurisdiction is
 * a legal explicit policy, not a validation error.
 */
@RestController
@RequestMapping(value = ApiConstants.PRICING, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class CommercialPricingAdminController {

    private final CommercialPricingService service;

    public CommercialPricingAdminController(CommercialPricingService service) {
        this.service = service;
    }

    // ---------------- price lists ----------------

    @PostMapping("/price-lists")
    @Operation(summary = "Create a price list", description = "A currency-scoped, window-bounded "
            + "set of unit-price overrides the placement resolves before the catalog's own price. "
            + "Inert until entries are added; the answer IS the created row (read-back).")
    public ResponseEntity<PriceListResponse> createPriceList(
            @Valid @RequestBody CreatePriceListRequest request) {
        PriceList saved = service.createPriceList(request.name(), request.currency(),
                request.active(), request.startsAt(), request.endsAt());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(PriceListResponse.of(saved));
    }

    @GetMapping("/price-lists")
    @Operation(summary = "List the price lists", description = "Every price list with its "
            + "currency, window and active flag — the operator's verification read.")
    public ResponseEntity<List<PriceListResponse>> listPriceLists() {
        return ResponseEntity.ok(service.listPriceLists().stream()
                .map(PriceListResponse::of).toList());
    }

    @PostMapping("/price-lists/{id}/entries")
    @Operation(summary = "Add a price-list entry", description = "One purchasable unit's "
            + "override inside the list (a variant entry or the product-level entry — one "
            + "entry per unit per list). The answer IS the created row.")
    public ResponseEntity<PriceListEntryResponse> addPriceListEntry(
            @PathVariable UUID id, @Valid @RequestBody CreatePriceListEntryRequest request) {
        PriceListEntry saved = service.addPriceListEntry(id, request.productId(),
                request.variantId(), request.amountMinor());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(PriceListEntryResponse.of(saved));
    }

    @GetMapping("/price-lists/{id}/entries")
    @Operation(summary = "List a price list's entries", description = "The list's override rows.")
    public ResponseEntity<List<PriceListEntryResponse>> listPriceListEntries(@PathVariable UUID id) {
        return ResponseEntity.ok(service.listPriceListEntries(id).stream()
                .map(PriceListEntryResponse::of).toList());
    }

    // ---------------- promotions ----------------

    @PostMapping("/promotions")
    @Operation(summary = "Create a promotion", description = "An order-level discount policy "
            + "denominated in one ISO-4217 currency: PERCENTAGE (basis points 1..10000) or "
            + "FIXED (minor units of that currency), an optional minimum spend and window. The "
            + "BEST single applicable promotion of the order's currency applies per order — "
            + "promotions never stack with each other.")
    public ResponseEntity<PromotionResponse> createPromotion(
            @Valid @RequestBody CreatePromotionRequest request) {
        Promotion saved = service.createPromotion(request.name(), request.currency(),
                request.discountType(), request.discountValue(), request.minSpendMinor(),
                request.startsAt(), request.endsAt(), request.active());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(PromotionResponse.of(saved));
    }

    @GetMapping("/promotions")
    @Operation(summary = "List the promotions", description = "Every promotion policy.")
    public ResponseEntity<List<PromotionResponse>> listPromotions() {
        return ResponseEntity.ok(service.listPromotions().stream()
                .map(PromotionResponse::of).toList());
    }

    // ---------------- vouchers ----------------

    @PostMapping("/vouchers")
    @Operation(summary = "Create a voucher", description = "A code-keyed discount denominated in "
            + "one ISO-4217 currency, with usage limits (global and per-user) and a window. "
            + "Redeemed at most once per order; the code is stored normalized (trimmed, "
            + "upper-cased); a voucher never applies to an order of another currency.")
    public ResponseEntity<VoucherResponse> createVoucher(
            @Valid @RequestBody CreateVoucherRequest request) {
        Voucher saved = service.createVoucher(request.code(), request.name(), request.currency(),
                request.discountType(), request.discountValue(), request.minSpendMinor(),
                request.startsAt(), request.endsAt(), request.maxRedemptions(),
                request.maxRedemptionsPerUser(), request.active());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(VoucherResponse.of(saved));
    }

    @GetMapping("/vouchers")
    @Operation(summary = "List the vouchers", description = "Every voucher policy.")
    public ResponseEntity<List<VoucherResponse>> listVouchers() {
        return ResponseEntity.ok(service.listVouchers().stream()
                .map(VoucherResponse::of).toList());
    }

    // ---------------- tax rates ----------------

    @PostMapping("/tax-rates")
    @Operation(summary = "Create a tax rate", description = "One jurisdiction's tax policy: a "
            + "2-letter country, basis points 0..10000 (a zero-rated jurisdiction is a legal "
            + "explicit policy), and INCLUSIVE (the component inside the displayed price) or "
            + "EXCLUSIVE (added on top). One ACTIVE row per country — activating a new rate "
            + "deactivates the previous one (history is never edited).")
    public ResponseEntity<TaxRateResponse> createTaxRate(
            @Valid @RequestBody CreateTaxRateRequest request) {
        TaxRate saved = service.createTaxRate(request.countryCode(), request.name(),
                request.rateBps(), request.taxMode(), request.active());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(TaxRateResponse.of(saved));
    }

    @GetMapping("/tax-rates")
    @Operation(summary = "List the tax rates", description = "Every jurisdiction policy, active "
            + "and superseded.")
    public ResponseEntity<List<TaxRateResponse>> listTaxRates() {
        return ResponseEntity.ok(service.listTaxRates().stream()
                .map(TaxRateResponse::of).toList());
    }

    // ---------------- request/response records ----------------

    public record CreatePriceListRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(min = 3, max = 3) String currency,
            Boolean active,
            Instant startsAt,
            Instant endsAt) {
    }

    public record CreatePriceListEntryRequest(
            @NotNull UUID productId,
            UUID variantId,
            @Positive long amountMinor) {
    }

    public record CreatePromotionRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Promotion.DiscountType discountType,
            @Positive long discountValue,
            Long minSpendMinor,
            Instant startsAt,
            Instant endsAt,
            Boolean active) {
    }

    public record CreateVoucherRequest(
            @NotBlank @Size(min = 4, max = 64) String code,
            @Size(max = 120) String name,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Voucher.DiscountType discountType,
            @Positive long discountValue,
            Long minSpendMinor,
            Instant startsAt,
            Instant endsAt,
            Long maxRedemptions,
            Long maxRedemptionsPerUser,
            Boolean active) {
    }

    public record CreateTaxRateRequest(
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            @NotBlank @Size(max = 120) String name,
            @PositiveOrZero @Max(10000) int rateBps,
            @NotNull TaxRate.TaxMode taxMode,
            Boolean active) {
    }

    public record PriceListResponse(UUID id, String name, String currency, boolean active,
                                    Instant startsAt, Instant endsAt) {
        static PriceListResponse of(PriceList list) {
            return new PriceListResponse(list.getId(), list.getName(), list.getCurrency(),
                    list.isActive(), list.getStartsAt(), list.getEndsAt());
        }
    }

    public record PriceListEntryResponse(UUID id, UUID priceListId, UUID productId,
                                         UUID variantId, long amountMinor) {
        static PriceListEntryResponse of(PriceListEntry entry) {
            return new PriceListEntryResponse(entry.getId(), entry.getPriceListId(),
                    entry.getProductId(), entry.getVariantId(), entry.getAmountMinor());
        }
    }

    public record PromotionResponse(UUID id, String name, String currency,
                                    Promotion.DiscountType discountType,
                                    long discountValue, long minSpendMinor,
                                    Instant startsAt, Instant endsAt, boolean active) {
        static PromotionResponse of(Promotion p) {
            return new PromotionResponse(p.getId(), p.getName(), p.getCurrency(),
                    p.getDiscountType(), p.getDiscountValue(), p.getMinSpendMinor(),
                    p.getStartsAt(), p.getEndsAt(), p.isActive());
        }
    }

    public record VoucherResponse(UUID id, String code, String name, String currency,
                                  Voucher.DiscountType discountType, long discountValue,
                                  long minSpendMinor, Instant startsAt, Instant endsAt,
                                  Long maxRedemptions, Long maxRedemptionsPerUser, boolean active) {
        static VoucherResponse of(Voucher v) {
            return new VoucherResponse(v.getId(), v.getCode(), v.getName(), v.getCurrency(),
                    v.getDiscountType(), v.getDiscountValue(), v.getMinSpendMinor(),
                    v.getStartsAt(), v.getEndsAt(), v.getMaxRedemptions(),
                    v.getMaxRedemptionsPerUser(), v.isActive());
        }
    }

    public record TaxRateResponse(UUID id, String countryCode, String name, int rateBps,
                                  TaxRate.TaxMode taxMode, boolean active) {
        static TaxRateResponse of(TaxRate r) {
            return new TaxRateResponse(r.getId(), r.getCountryCode(), r.getName(), r.getRateBps(),
                    r.getTaxMode(), r.isActive());
        }
    }
}
