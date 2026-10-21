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
        Voucher.DiscountType type = normalizeType(command.discountValueType(), errors, true);
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
        Voucher created = voucherRepository.save(Voucher.of(code, name, currency, type,
                discountValue, minSpend, startsAt, endsAt, usageLimit,
                Boolean.TRUE.equals(command.applyOncePerCustomer()) ? 1L : null, true));
        return VoucherMutationResult.success(toView(created));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public VoucherMutationResult updateVoucher(UUID id, VoucherUpdateCommand command) {
        Optional<Voucher> addressed = id == null ? Optional.empty() : voucherRepository.findById(id);
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
        if (command.code() != null && !command.code().isBlank()
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
                ? normalizeType(command.discountValueType(), errors, false)
                : row.getDiscountType();
        long mergedValue = command.discountValue() == null
                ? row.getDiscountValue()
                : normalizeDiscountValue(command.discountValue(), mergedType, errors);
        String mergedCurrency = command.currency() == null
                ? row.getCurrency()
                : normalizeCurrency(command.currency(), errors);
        long mergedMinSpend = command.minSpend() == null ? row.getMinSpendMinor() : command.minSpend();
        if (mergedMinSpend < 0) {
            errors.add(new VoucherMutationError("minSpend", VoucherMutationErrorCode.INVALID,
                    "A voucher's minimum spend cannot be negative"));
        }
        Instant mergedStarts = command.startDate() == null ? row.getStartsAt()
                : parseInstant(command.startDate(), "startDate", errors);
        Instant mergedEnds = command.endDate() == null ? row.getEndsAt()
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
        String normalized = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() < CODE_MIN_LENGTH || normalized.length() > CODE_MAX_LENGTH) {
            errors.add(new VoucherMutationError("code", VoucherMutationErrorCode.REQUIRED,
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
                                                      boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.add(new VoucherMutationError("discountValueType", VoucherMutationErrorCode.REQUIRED,
                        "A voucher requires a discount value type"));
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
        String normalized = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            errors.add(new VoucherMutationError("currency", VoucherMutationErrorCode.REQUIRED,
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
