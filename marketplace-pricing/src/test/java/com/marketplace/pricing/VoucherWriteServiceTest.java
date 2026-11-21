package com.marketplace.pricing;

import com.marketplace.pricing.spi.VoucherCreateCommand;
import com.marketplace.pricing.spi.VoucherMutationError;
import com.marketplace.pricing.spi.VoucherMutationErrorCode;
import com.marketplace.pricing.spi.VoucherMutationResult;
import com.marketplace.pricing.spi.VoucherUpdateCommand;
import com.marketplace.pricing.spi.VoucherWriteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucherCreate},
 * {@code voucherUpdate}, {@code voucherDelete} and
 * {@code voucherBulkDelete} mutation roots): the owning write use case's
 * unit proof — the JUnit 5 + Mockito style the module's own tests
 * established (no Spring context: the {@code @PreAuthorize} proxy is an
 * integration concern proven by the app module's real-stack tests).
 *
 * <p>Coverage target: every method branch reachable through the typed
 * seam — the create's full validation ladder (missing/short/malformed
 * code, missing currency/type/value, the percentage and fixed bounds,
 * the negative minimum spend, the unparseable instants, the reversed
 * window, the duplicate code, the happy path's normalization and
 * per-user flattening), the update's partial-preserve semantics (the
 * empty no-op, the merged-window span re-check, the code-stability
 * refusal on a redeemed row, the duplicate-code negative excluding the
 * row's own id, the applyOncePerCustomer flattening both ways), the
 * delete's NOT_FOUND and soft-delete happy path, and the bulk delete's
 * honest count.</p>
 */
class VoucherWriteServiceTest {

    private VoucherRepository voucherRepository;
    private VoucherRedemptionRepository redemptionRepository;
    private VoucherWriteSpi service;

    @BeforeEach
    void setUp() {
        voucherRepository = mock(VoucherRepository.class);
        redemptionRepository = mock(VoucherRedemptionRepository.class);
        // The 2026-11-06 #810 round review: the insert's own REQUIRES_NEW
        // transaction — a stubbed manager executes the template's callback
        // (getTransaction -> status, commit -> no-op) so the unit tests run
        // the REAL arbitration shape without a database.
        org.springframework.transaction.PlatformTransactionManager transactionManager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        service = new VoucherWriteService(voucherRepository, redemptionRepository,
                transactionManager);
    }

    private static Voucher row(String code) {
        return Voucher.of(code, "Row", "SAR", Voucher.DiscountType.FIXED, 500L, 0L,
                null, null, null, null, true);
    }

    private static VoucherCreateCommand create(String code, String type, Long value, String currency) {
        return new VoucherCreateCommand("Policy", code, type, value, currency, null,
                null, null, null, null);
    }

    // ------------------------------------------------------------------
    // createVoucher — the validation ladder
    // ------------------------------------------------------------------

    @Test
    void shortCodeAnswersTheInvalidDomainError() {
        // The 2026-11-05 #810 round review: a PRESENT code outside the
        // length bound answers INVALID — the caller sent a value, the
        // value is malformed; REQUIRED stays the ABSENT-code verdict.
        VoucherMutationResult result = service.createVoucher(create("AB", "FIXED", 500L, "SAR"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.INVALID, "code");
        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void malformedCodeAnswersTheInvalidDomainError() {
        VoucherMutationResult result = service.createVoucher(create("BAD CODE!", "FIXED", 500L, "SAR"));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.INVALID, "code");
    }

    @Test
    void missingValueTypeAnswersTheRequiredDomainError() {
        VoucherMutationResult result = service.createVoucher(create("TEST-CODE", null, 500L, "SAR"));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field, VoucherMutationError::code)
                .containsExactly("discountValueType", VoucherMutationErrorCode.REQUIRED);
    }

    @Test
    void unknownValueTypeAnswersTheInvalidDomainError() {
        VoucherMutationResult result = service.createVoucher(create("TEST-CODE", "WEIRD", 500L, "SAR"));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code)
                .isEqualTo(VoucherMutationErrorCode.INVALID);
    }

    @Test
    void percentageBoundsAreEnforced() {
        VoucherMutationResult over = service.createVoucher(create("TEST-CODE", "PERCENTAGE", 10001L, "SAR"));
        assertThat(over.errors()).singleElement()
                .extracting(VoucherMutationError::field, VoucherMutationError::code)
                .containsExactly("discountValue", VoucherMutationErrorCode.INVALID);

        VoucherMutationResult zero = service.createVoucher(create("TEST-CODE", "PERCENTAGE", 0L, "SAR"));
        assertThat(zero.errors()).singleElement()
                .extracting(VoucherMutationError::code)
                .isEqualTo(VoucherMutationErrorCode.INVALID);
    }

    @Test
    void fixedValueMustBePositive() {
        VoucherMutationResult result = service.createVoucher(create("TEST-CODE", "FIXED", 0L, "SAR"));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field, VoucherMutationError::code)
                .containsExactly("discountValue", VoucherMutationErrorCode.INVALID);
    }

    @Test
    void malformedCurrencyAnswersTheInvalidDomainError() {
        // The 2026-11-08 #810 round-5 test nitpick: the assertion pins the
        // CODE too — a regression that answers REQUIRED (the missing-value
        // vocabulary) for a MALFORMED value used to pass the field-only
        // assertion.
        VoucherMutationResult result = service.createVoucher(create("TEST-CODE", "FIXED", 500L, "riyals"));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field, VoucherMutationError::code)
                .containsExactly("currency", VoucherMutationErrorCode.INVALID);
    }

    @Test
    void reversedWindowAnswersTheInvalidDomainError() {
        VoucherMutationResult result = service.createVoucher(new VoucherCreateCommand(
                "Policy", "TEST-CODE", "FIXED", 500L, "SAR", null,
                "2026-11-02T00:00:00Z", "2026-11-01T00:00:00Z", null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field, VoucherMutationError::code)
                .containsExactly("endDate", VoucherMutationErrorCode.INVALID);
    }

    @Test
    void unparseableInstantAnswersTheInvalidDomainError() {
        VoucherMutationResult result = service.createVoucher(new VoucherCreateCommand(
                "Policy", "TEST-CODE", "FIXED", 500L, "SAR", null,
                "not-a-date", null, null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field)
                .isEqualTo("startDate");
    }

    @Test
    void negativeMinSpendAnswersTheInvalidDomainError() {
        VoucherMutationResult result = service.createVoucher(new VoucherCreateCommand(
                "Policy", "TEST-CODE", "FIXED", 500L, "SAR", -1L,
                null, null, null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field)
                .isEqualTo("minSpend");
    }

    @Test
    void nonPositiveUsageLimitAnswersTheInvalidDomainError() {
        VoucherMutationResult result = service.createVoucher(new VoucherCreateCommand(
                "Policy", "TEST-CODE", "FIXED", 500L, "SAR", null,
                null, null, 0L, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field)
                .isEqualTo("usageLimit");
    }

    @Test
    void duplicateCodeAnswersTheAlreadyExistsDomainError() {
        when(voucherRepository.findByCode("TEST-CODE")).thenReturn(Optional.of(row("TEST-CODE")));

        VoucherMutationResult result = service.createVoucher(create("TEST-CODE", "FIXED", 500L, "SAR"));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.ALREADY_EXISTS, "code");
        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void theLosingSideOfAUniqueCodeRaceAnswersTheAlreadyExistsDomainError() {
        // The 2026-11-06 #810 round review (Minor · Data Integrity): the
        // INSERT now flushes INSIDE its own REQUIRES_NEW transaction, so
        // the unique-index loser's 23505 violation actually REACHES the
        // method's catch (the deferred commit-time flush used to escape to
        // the caller as the raw database error).
        when(voucherRepository.findByCode("TEST-CODE")).thenReturn(Optional.empty());
        java.sql.SQLException unique = new java.sql.SQLException(
                "duplicate key value violates unique constraint \"uq_vouchers_code\"",
                "23505");
        when(voucherRepository.saveAndFlush(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement", unique));

        VoucherMutationResult result = service.createVoucher(create("TEST-CODE", "FIXED", 500L, "SAR"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.ALREADY_EXISTS, "code");
    }

    @Test
    void happyCreateNormalizesTheCodeAndAnswersTheProjection() {
        when(voucherRepository.findByCode("TEST-CODE")).thenReturn(Optional.empty());
        when(voucherRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(redemptionRepository.countByVoucherId(any(UUID.class))).thenReturn(0L);

        VoucherMutationResult result = service.createVoucher(new VoucherCreateCommand(
                "Policy", "  test-code  ", "percentage", 1500L, "sar", null,
                "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", 10L, true));

        assertThat(result.success()).isTrue();
        assertThat(result.voucher().code()).isEqualTo("TEST-CODE");
        assertThat(result.voucher().currency()).isEqualTo("SAR");
        assertThat(result.voucher().discountValueType())
                .isEqualTo(com.marketplace.pricing.spi.VoucherView.DiscountType.PERCENTAGE);
        assertThat(result.voucher().usageLimit()).isEqualTo(10L);
        assertThat(result.voucher().applyOncePerCustomer()).isTrue();
        assertThat(result.voucher().startsAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(result.voucher().endsAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        verify(voucherRepository).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // updateVoucher — the partial-preserve semantics
    // ------------------------------------------------------------------

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(voucherRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        VoucherMutationResult result = service.updateVoucher(unknown,
                new VoucherUpdateCommand("New", null, null, null, null, null,
                        null, null, null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void emptyCommandAnswersTheCurrentProjectionWithoutASave() {
        Voucher existing = row("KEEP-CODE");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(2L);

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, null, null, null, null, null,
                        null, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.voucher().code()).isEqualTo("KEEP-CODE");
        assertThat(result.voucher().timesUsed()).isEqualTo(2L);
        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void providedFieldsReplaceAndNullsPreserve() {
        Voucher existing = row("KEEP-CODE");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(voucherRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(0L);

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand("Renamed", null, null, null, null, null,
                        null, null, 5L, null));

        assertThat(result.success()).isTrue();
        assertThat(result.voucher().name()).isEqualTo("Renamed");
        assertThat(result.voucher().code()).isEqualTo("KEEP-CODE");
        assertThat(result.voucher().usageLimit()).isEqualTo(5L);
    }

    /**
     * The 2026-11-04 #806 round (Major · Functional Correctness): a
     * currency change without the amounts restated keeps the row's
     * minor-unit values under the NEW currency's unit — the silent
     * 100x-class corruption (500 SAR minor becomes "500 USD minor").
     * The platform has no FX source, so the honest contract is the
     * explicit restatement: the currency leg demands BOTH amounts.
     */
    @Test
    void aCurrencyChangeWithoutTheAmountsRestatedAnswersTheClassifiedInvalid() {
        Voucher existing = row("FX-KEEP");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(0L);

        VoucherMutationResult rejected = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, null, null, null, "USD", null,
                        null, null, null, null));

        assertThat(rejected.success()).isFalse();
        assertThat(rejected.errors()).anySatisfy(error -> {
            assertThat(error.field()).isEqualTo("currency");
            assertThat(error.code()).isEqualTo(VoucherMutationErrorCode.INVALID);
        });
        verify(voucherRepository, never()).saveAndFlush(any());

        // The same currency restated WITH both amounts lands — the
        // operator's explicit correction path stays open. The view's own
        // vocabulary carries no amounts (the settlement path's internal
        // facts), so the restated pair is verified on the persisted row.
        when(voucherRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        VoucherMutationResult restated = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, null, null, 1000L, "USD", 250L,
                        null, null, null, null));

        assertThat(restated.success()).isTrue();
        assertThat(restated.voucher().currency()).isEqualTo("USD");
        verify(voucherRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(saved ->
                "USD".equals(saved.getCurrency())
                        && saved.getDiscountValue() == 1000L
                        && saved.getMinSpendMinor() == 250L));
    }

    @Test
    void mergedWindowSpanRecheckRunsOverTheMergedPair() {
        Voucher existing = Voucher.of("SPAN-CODE", "Row", "SAR", Voucher.DiscountType.FIXED, 500L, 0L,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                null, null, true);
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));

        // The new end lands BEFORE the preserved start — the merged span is reversed.
        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, null, null, null, null, null,
                        null, "2026-10-01T00:00:00Z", null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::field, VoucherMutationError::code)
                .containsExactly("endDate", VoucherMutationErrorCode.INVALID);
    }

    @Test
    void codeChangeOnARedeemedVoucherIsRefused() {
        Voucher existing = row("USED-CODE");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(3L);

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, "FRESH-CODE", null, null, null, null,
                        null, null, null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.VOUCHER_ALREADY_USED, "code");
        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void malformedCodeOnARedeemedRowAnswersTheShapeErrorNeverAnNpe() {
        Voucher existing = row("USED-CODE");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(3L);

        // The command's code is non-blank but fails the entity's shape —
        // normalizeCode answers null WITH its recorded payload error, so
        // the code-stability re-check must guard the null merged code and
        // let the failure ladder answer (never a NullPointerException).
        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, "BAD CODE!", null, null, null, null,
                        null, null, null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.INVALID, "code");
        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void typeChangeAloneRevalidatesThePreservedValueAgainstTheNewBounds() {
        // A FIXED 50000-minor row switched to PERCENTAGE WITHOUT a new
        // value: the preserved 50000 fails the percentage 1..10000
        // basis-points bound — the MERGED (type, value) pair answers the
        // INVALID error, never an out-of-range row.
        Voucher existing = Voucher.of("TYPE-SWITCH", "Row", "SAR",
                Voucher.DiscountType.FIXED, 50000L, 0L, null, null, null, null, true);
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, null, "PERCENTAGE", null, null, null,
                        null, null, null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code, VoucherMutationError::field)
                .containsExactly(VoucherMutationErrorCode.INVALID, "discountValue");
        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void typeChangeWithThePreservedValueInsideTheNewBoundsSaves() {
        // The mirrored happy path: a FIXED 500-minor row switched to
        // PERCENTAGE alone — 500 sits inside the 1..10000 basis-points
        // bound, so the merged pair persists.
        Voucher existing = Voucher.of("TYPE-FITS", "Row", "SAR",
                Voucher.DiscountType.FIXED, 500L, 0L, null, null, null, null, true);
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(voucherRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(0L);

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, null, "PERCENTAGE", null, null, null,
                        null, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.voucher().discountValueType())
                .isEqualTo(com.marketplace.pricing.spi.VoucherView.DiscountType.PERCENTAGE);
        verify(voucherRepository).saveAndFlush(any());
    }

    @Test
    void codeChangeOnAnUnredeemedVoucherExecutes() {
        Voucher existing = row("IDLE-CODE");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(voucherRepository.findByCode("FRESH-CODE")).thenReturn(Optional.empty());
        when(voucherRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(0L);

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, "fresh-code", null, null, null, null,
                        null, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.voucher().code()).isEqualTo("FRESH-CODE");
    }

    @Test
    void duplicateCodeExcludingTheRowsOwnIdAnswersAlreadyExists() {
        Voucher existing = row("OWN-CODE");
        Voucher other = row("OTHER-CODE");
        when(voucherRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(voucherRepository.findByCode("OTHER-CODE")).thenReturn(Optional.of(other));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(0L);

        VoucherMutationResult result = service.updateVoucher(existing.getId(),
                new VoucherUpdateCommand(null, "OTHER-CODE", null, null, null, null,
                        null, null, null, null));

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code)
                .isEqualTo(VoucherMutationErrorCode.ALREADY_EXISTS);
    }

    @Test
    void applyOncePerCustomerFlattensBothWays() {
        Voucher perUser = Voucher.of("PERU-CODE", "Row", "SAR", Voucher.DiscountType.FIXED, 500L, 0L,
                null, null, null, 1L, true);
        when(voucherRepository.findByIdForUpdate(perUser.getId())).thenReturn(Optional.of(perUser));
        when(voucherRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(redemptionRepository.countByVoucherId(perUser.getId())).thenReturn(0L);

        // true -> 1 (rendered back as applyOncePerCustomer=true) ...
        VoucherMutationResult flattened = service.updateVoucher(perUser.getId(),
                new VoucherUpdateCommand(null, null, null, null, null, null,
                        null, null, null, true));
        assertThat(flattened.voucher().applyOncePerCustomer()).isTrue();

        // ... and false -> unlimited per-user (rendered back as false).
        VoucherMutationResult released = service.updateVoucher(perUser.getId(),
                new VoucherUpdateCommand(null, null, null, null, null, null,
                        null, null, null, false));
        assertThat(released.voucher().applyOncePerCustomer()).isFalse();
        // The 2026-11-05 #810 round review: the STORED row carries the
        // honest null — the projection alone used to pass even when the
        // revision kept the stale per-user limit (2) behind the false
        // flag. The entity's own row is the stored truth (the view's
        // applyOncePerCustomer flattening cannot see N>1 vs unlimited).
        assertThat(perUser.getMaxRedemptionsPerUser()).isNull();
    }

    // ------------------------------------------------------------------
    // deleteVoucher / deleteVouchers — the soft-delete discipline
    // ------------------------------------------------------------------

    @Test
    void deleteAnswersTheLastKnownProjectionAndSoftDeletesTheRow() {
        Voucher existing = row("GONE-CODE");
        when(voucherRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(redemptionRepository.countByVoucherId(existing.getId())).thenReturn(1L);

        VoucherMutationResult result = service.deleteVoucher(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.voucher().code()).isEqualTo("GONE-CODE");
        assertThat(result.voucher().timesUsed()).isEqualTo(1L);
        verify(voucherRepository).delete(existing);
    }

    @Test
    void deleteOfAnUnknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(voucherRepository.findById(unknown)).thenReturn(Optional.empty());

        VoucherMutationResult result = service.deleteVoucher(unknown);

        assertThat(result.errors()).singleElement()
                .extracting(VoucherMutationError::code)
                .isEqualTo(VoucherMutationErrorCode.NOT_FOUND);
    }

    @Test
    void bulkDeleteCountsOnlyTheRowsTheOwnerItselfDeleted() {
        Voucher first = row("BULK-ONE1");
        Voucher second = row("BULK-TWO2");
        UUID unknown = UUID.randomUUID();
        when(voucherRepository.findById(first.getId())).thenReturn(Optional.of(first));
        when(voucherRepository.findById(second.getId())).thenReturn(Optional.of(second));
        when(voucherRepository.findById(unknown)).thenReturn(Optional.empty());

        int count = service.deleteVouchers(List.of(first.getId(), unknown, second.getId()));

        assertThat(count).isEqualTo(2);
        verify(voucherRepository).delete(first);
        verify(voucherRepository).delete(second);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZeroWithoutTouchingTheOwner() {
        assertThat(service.deleteVouchers(List.of())).isZero();
        assertThat(service.deleteVouchers(null)).isZero();
        verify(voucherRepository, never()).delete(any());
    }
}
