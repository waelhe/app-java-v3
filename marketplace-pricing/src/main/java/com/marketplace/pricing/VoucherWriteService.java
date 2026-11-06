package com.marketplace.pricing;

import com.marketplace.pricing.spi.VoucherCreateCommand;
import com.marketplace.pricing.spi.VoucherMutationError;
import com.marketplace.pricing.spi.VoucherMutationErrorCode;
import com.marketplace.pricing.spi.VoucherMutationResult;
import com.marketplace.pricing.spi.VoucherUpdateCommand;
import com.marketplace.pricing.spi.VoucherView;
import com.marketplace.pricing.spi.VoucherWriteSpi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucherCreate},
 * {@code voucherUpdate}, {@code voucherDelete} and
 * {@code voucherBulkDelete} mutation roots): the owning use case for the
 * voucher policy collection's ADMIN write surface. These are Saleor
 * MANAGEMENT roots ("Requires one of the following permissions:
 * MANAGE_DISCOUNTS") mapped through the platform's ADMIN capability and
 * enforced HERE at the owning use-case boundary by Spring Security's
 * method security — the same {@code @PreAuthorize} mechanism the
 * module's own discount reads established.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * entity factory's own shapes (the code's 4..64 uppercase/digits/dashes
 * normalization, the ISO-4217 currency, the PERCENTAGE 1..10000
 * basis-points / FIXED positive-minor bounds, the non-negative minimum
 * spend, the positive-or-null redemption limits, the starts-before-ends
 * span) re-checked BEFORE the write, with the table's own CHECK
 * constraints as the concurrent-write backstop. Domain outcomes answer
 * as payload error entries (the registered "Mutation payload errors"
 * ADAPT); transport concerns never reach this service.</p>
 *
 * <p><b>The code-stability discipline</b> (the entity's own documented
 * law): an already-redeemed voucher's code never moves — a code change
 * on a row with redemptions answers the {@code VOUCHER_ALREADY_USED}
 * domain error, the Saleor vocabulary's own member for exactly this
 * refusal. <b>Update is partial</b> — null command fields preserve the
 * column, and the span re-check runs over the MERGED window. The
 * create's {@code applyOncePerCustomer=true} flattens the per-user limit
 * to 1 (the read side's own flattening semantics); the honest usage
 * count on every projection is the redemption rows COUNTED, never a
 * stored counter.</p>
 */
@Service
public class VoucherWriteService implements VoucherWriteSpi {

    private static final int CODE_MAX_LENGTH = 64;
    private static final int CODE_MIN_LENGTH = 4;
    private static final int NAME_MAX_LENGTH = 120;

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;

    public VoucherWriteService(VoucherRepository voucherRepository,
                               VoucherRedemptionRepository redemptionRepository) {
        this.voucherRepository = voucherRepository;
        this.redemptionRepository = redemptionRepository;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public VoucherMutationResult createVoucher(VoucherCreateCommand command) {
        List<VoucherMutationError> errors = new ArrayList<>();
        String code = normalizeCode(command.code(), errors);
        Voucher.DiscountType type = normalizeType(command.discountValueType(), errors, true, false);
        String currency = normalizeCurrency(command.currency(), errors);
        long discountValue = normalizeDiscountValue(command.discountValue(), type, errors);
        long minSpend = command.minSpend() == null ? 0L : command.minSpend();
        if (minSpend < 0) {
            errors.add(new VoucherMutationError("minSpend", VoucherMutationErrorCode.INVALID,
                    "A voucher's minimum spend cannot be negative"));
        }
        Instant startsAt = parseInstant(command.startDate(), "startDate", errors);
        Instant endsAt = parseInstant(command.endDate(), "endDate", errors);
        Long usageLimit = normalizePositive(command.usageLimit(), "usageLimit", errors);
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            errors.add(new VoucherMutationError("endDate", VoucherMutationErrorCode.INVALID,
                    "A voucher window requires startDate before endDate"));
        }
        if (code != null && voucherRepository.findByCode(code).isPresent()) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.ALREADY_EXISTS,
                    "A voucher with the code already exists"));
        }
        String name = command.name() == null ? null : command.name().strip();
        if (name != null && name.length() > NAME_MAX_LENGTH) {
            errors.add(new VoucherMutationError("name", VoucherMutationErrorCode.INVALID,
                    "A voucher name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (!errors.isEmpty()) {
            return VoucherMutationResult.failure(errors);
        }
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the code-reservation RACE — two concurrent creates
        // (or a create racing an update's new code) both pass the
        // findByCode probe and the loser dies on the unique index at
        // flush; the SAME one classifier the platform's surfaces share
        // answers the payload's own ALREADY_EXISTS instead of the raw
        // database error.
        Voucher created;
        try {
            created = voucherRepository.save(Voucher.of(code, name, currency, type,
                    discountValue, minSpend, startsAt, endsAt, usageLimit,
                    Boolean.TRUE.equals(command.applyOncePerCustomer()) ? 1L : null, true));
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            if (com.marketplace.shared.api.GlobalExceptionHandler.isUniqueViolation(race)) {
                return VoucherMutationResult.failure(List.of(new VoucherMutationError(
                        "code", VoucherMutationErrorCode.ALREADY_EXISTS,
                        "A voucher with the code already exists")));
            }
            throw race;
        }
        return VoucherMutationResult.success(toView(created));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public VoucherMutationResult updateVoucher(UUID id, VoucherUpdateCommand command) {
        // The 2026-11-03 adversarial scan: the code-stability verdict rides
        // the SAME row lock the settlement path (settleVoucher's
        // findByCodeForUpdate) serializes on — an unlocked check-then-act
        // let a concurrent redemption slip between the count and the
        // revise.
        Optional<Voucher> addressed = id == null ? Optional.empty() : voucherRepository.findByIdForUpdate(id);
        if (addressed.isEmpty()) {
            return VoucherMutationResult.failure(List.of(new VoucherMutationError(
                    "id", VoucherMutationErrorCode.NOT_FOUND, "No voucher found for the id")));
        }
        if (command.isEmpty()) {
            return VoucherMutationResult.success(toView(addressed.get()));
        }
        Voucher row = addressed.get();
        List<VoucherMutationError> errors = new ArrayList<>();

        String mergedCode = command.code() == null ? row.getCode() : normalizeCode(command.code(), errors);
        // The merged code is null when the command's own code failed the
        // entity's shape (recorded above as its payload error) — guarded
        // here so the code-stability re-check never dereferences it.
        if (command.code() != null && !command.code().isBlank()
                && mergedCode != null
                && !mergedCode.equals(row.getCode())
                && redemptionRepository.countByVoucherId(row.getId()) > 0) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.VOUCHER_ALREADY_USED,
                    "An already-redeemed voucher's code cannot change"));
        }
        String mergedName = command.name() == null ? row.getName() : command.name().strip();
        if (mergedName != null && mergedName.length() > NAME_MAX_LENGTH) {
            errors.add(new VoucherMutationError("name", VoucherMutationErrorCode.INVALID,
                    "A voucher name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        boolean typeChanged = command.discountValueType() != null;
        Voucher.DiscountType mergedType = typeChanged
                ? normalizeType(command.discountValueType(), errors, false, true)
                : row.getDiscountType();
        // The MERGED (type, value) pair is re-validated together — never
        // only the command's value: an update that changes the type alone
        // re-runs the row's preserved value through the new type's own
        // bounds (a FIXED 50000-minor row switched to PERCENTAGE answers
        // the basis-points INVALID error, never an out-of-range row).
        Long candidateValue = command.discountValue() == null
                ? row.getDiscountValue()
                : command.discountValue();
        long mergedValue = normalizeDiscountValue(candidateValue, mergedType, errors);
        String mergedCurrency = command.currency() == null
                ? row.getCurrency()
                : normalizeCurrency(command.currency(), errors);
        // The 2026-11-04 #806 round (Major · Functional Correctness): a
        // currency change without the amounts restated keeps the ROW's
        // minor-unit values under the NEW currency's unit — a silent
        // 100x-class money corruption (1000 USD cents saved as "1000 JPY").
        // The platform has no FX rate source (documented), so the honest
        // contract is the explicit restatement: changing the currency
        // requires BOTH the discount value and the minimum spend in the
        // new currency — the same re-validation-together discipline the
        // (type, value) pair above carries.
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the change verdict compares the NORMALIZED merged
        // currency — a lowercased "sar" against a stored "SAR" row used to
        // read as a CHANGE and demand the restatement the caller already
        // carried (the same currency, differently cased).
        if (command.currency() != null
                && mergedCurrency != null
                && !mergedCurrency.equals(row.getCurrency())
                && (command.discountValue() == null || command.minSpend() == null)) {
            errors.add(new VoucherMutationError("currency", VoucherMutationErrorCode.INVALID,
                    "Changing a voucher's currency requires restating both its discountValue "
                            + "and minSpend in the new currency"));
        }
        long mergedMinSpend = command.minSpend() == null ? row.getMinSpendMinor() : command.minSpend();
        if (mergedMinSpend < 0) {
            errors.add(new VoucherMutationError("minSpend", VoucherMutationErrorCode.INVALID,
                    "A voucher's minimum spend cannot be negative"));
        }
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): a BLANK date string is "no information" exactly
        // like an absent one — the old path parsed it to null, SKIPPED the
        // window check on the null leg, and let the row keep a stored
        // start that the new endDate now preceded (the flush's own
        // constraint answered the raw database error). The blank merges to
        // the KEPT value and the window check sees the merged truth.
        Instant mergedStarts = command.startDate() == null || command.startDate().isBlank()
                ? row.getStartsAt()
                : parseInstant(command.startDate(), "startDate", errors);
        Instant mergedEnds = command.endDate() == null || command.endDate().isBlank()
                ? row.getEndsAt()
                : parseInstant(command.endDate(), "endDate", errors);
        Long mergedUsageLimit = command.usageLimit() == null
                ? row.getMaxRedemptions()
                : normalizePositive(command.usageLimit(), "usageLimit", errors);
        Long mergedPerUser;
        if (command.applyOncePerCustomer() == null) {
            mergedPerUser = row.getMaxRedemptionsPerUser();
        } else {
            mergedPerUser = command.applyOncePerCustomer() ? 1L : null;
        }
        if (mergedStarts != null && mergedEnds != null && !mergedStarts.isBefore(mergedEnds)) {
            errors.add(new VoucherMutationError("endDate", VoucherMutationErrorCode.INVALID,
                    "A voucher window requires startDate before endDate"));
        }
        if (mergedCode != null && !mergedCode.equals(row.getCode())
                && voucherRepository.findByCode(mergedCode)
                        .filter(existing -> !existing.getId().equals(row.getId()))
                        .isPresent()) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.ALREADY_EXISTS,
                    "A voucher with the code already exists"));
        }
        if (!errors.isEmpty()) {
            return VoucherMutationResult.failure(errors);
        }
        row.revise(mergedCode, mergedName, mergedType, mergedValue, mergedCurrency,
                mergedMinSpend, mergedStarts, mergedEnds, mergedUsageLimit, mergedPerUser);
        return VoucherMutationResult.success(toView(voucherRepository.save(row)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public VoucherMutationResult deleteVoucher(UUID id) {
        Optional<Voucher> addressed = id == null ? Optional.empty() : voucherRepository.findById(id);
        if (addressed.isEmpty()) {
            return VoucherMutationResult.failure(List.of(new VoucherMutationError(
                    "id", VoucherMutationErrorCode.NOT_FOUND, "No voucher found for the id")));
        }
        Voucher row = addressed.get();
        VoucherView lastKnown = toView(row);
        voucherRepository.delete(row);
        return VoucherMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteVouchers(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            Optional<Voucher> addressed = id == null ? Optional.empty() : voucherRepository.findById(id);
            if (addressed.isPresent()) {
                voucherRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------
    // the validation vocabulary — the entity factory's own shapes
    // ------------------------------------------------------------------

    private static String normalizeCode(String raw, List<VoucherMutationError> errors) {
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the vocabulary distinction — an ABSENT code is
        // REQUIRED, a PRESENT code outside the length bound is INVALID
        // (the caller sent a value; the value is malformed).
        if (raw == null || raw.isBlank()) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.REQUIRED,
                    "A voucher code is " + CODE_MIN_LENGTH + ".." + CODE_MAX_LENGTH + " characters"));
            return null;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() < CODE_MIN_LENGTH || normalized.length() > CODE_MAX_LENGTH) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.INVALID,
                    "A voucher code is " + CODE_MIN_LENGTH + ".." + CODE_MAX_LENGTH + " characters"));
            return null;
        }
        if (!normalized.matches("[A-Z0-9-]+")) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.INVALID,
                    "A voucher code is uppercase letters, digits and dashes"));
            return null;
        }
        return normalized;
    }

    private static Voucher.DiscountType normalizeType(String raw, List<VoucherMutationError> errors,
                                                      boolean required, boolean blankIsInvalid) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.add(new VoucherMutationError("discountValueType", VoucherMutationErrorCode.REQUIRED,
                        "A voucher requires a discount value type"));
            } else if (blankIsInvalid && raw != null) {
                // The 2026-11-05 #810 round review (Minor · Functional
                // Correctness): an update that SENDS a blank type answers
                // INVALID — the old path silently merged null and let the
                // flush hit the NOT NULL column's raw database error.
                errors.add(new VoucherMutationError("discountValueType",
                        VoucherMutationErrorCode.INVALID,
                        "A voucher's discount value type is PERCENTAGE or FIXED"));
            }
            return null;
        }
        try {
            return Voucher.DiscountType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.add(new VoucherMutationError("discountValueType", VoucherMutationErrorCode.INVALID,
                    "A voucher's discount value type is PERCENTAGE or FIXED"));
            return null;
        }
    }

    private static String normalizeCurrency(String raw, List<VoucherMutationError> errors) {
        // The 2026-11-05 #810 round review: the same vocabulary
        // distinction the code carries — absent is REQUIRED, present and
        // malformed is INVALID.
        if (raw == null || raw.isBlank()) {
            errors.add(new VoucherMutationError("currency", VoucherMutationErrorCode.REQUIRED,
                    "A voucher is denominated in a 3-letter ISO-4217 currency"));
            return null;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            errors.add(new VoucherMutationError("currency", VoucherMutationErrorCode.INVALID,
                    "A voucher is denominated in a 3-letter ISO-4217 currency"));
            return null;
        }
        return normalized;
    }

    private static long normalizeDiscountValue(Long raw, Voucher.DiscountType type,
                                               List<VoucherMutationError> errors) {
        if (raw == null) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.REQUIRED,
                    "A voucher requires a discount value"));
            return 0L;
        }
        if (type == Voucher.DiscountType.PERCENTAGE && (raw <= 0 || raw > 10000)) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "A percentage voucher carries 1..10000 basis points"));
            return 0L;
        }
        if (type == Voucher.DiscountType.FIXED && raw <= 0) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "A fixed voucher carries a positive minor amount"));
            return 0L;
        }
        if (type == null && raw <= 0) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "A voucher's discount value is positive"));
            return 0L;
        }
        return raw;
    }

    private static Long normalizePositive(Long raw, String field, List<VoucherMutationError> errors) {
        if (raw != null && raw <= 0) {
            errors.add(new VoucherMutationError(field, VoucherMutationErrorCode.INVALID,
                    "A voucher's " + field + " is positive or null"));
        }
        return raw;
    }

    private static Instant parseInstant(String raw, String field, List<VoucherMutationError> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException exception) {
            errors.add(new VoucherMutationError(field, VoucherMutationErrorCode.INVALID,
                    field + " must be an ISO-8601 instant"));
            return null;
        }
    }

    /** The bounded projection — the read service's own rendering rules. */
    private VoucherView toView(Voucher voucher) {
        return new VoucherView(
                voucher.getId(),
                voucher.getCode(),
                voucher.getName(),
                voucher.getCurrency(),
                voucher.getDiscountType() == Voucher.DiscountType.PERCENTAGE
                        ? VoucherView.DiscountType.PERCENTAGE
                        : VoucherView.DiscountType.FIXED,
                voucher.getMaxRedemptions(),
                redemptionRepository.countByVoucherId(voucher.getId()),
                voucher.getMaxRedemptionsPerUser() != null && voucher.getMaxRedemptionsPerUser() == 1L,
                voucher.getStartsAt(),
                voucher.getEndsAt());
    }
}
