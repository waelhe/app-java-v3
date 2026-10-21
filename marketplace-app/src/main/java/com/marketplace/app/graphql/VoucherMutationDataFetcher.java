package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.DiscountError;
import com.marketplace.app.graphql.generated.types.DiscountErrorCode;
import com.marketplace.app.graphql.generated.types.VoucherBulkDelete;
import com.marketplace.app.graphql.generated.types.VoucherCreate;
import com.marketplace.app.graphql.generated.types.VoucherDelete;
import com.marketplace.app.graphql.generated.types.VoucherInput;
import com.marketplace.app.graphql.generated.types.VoucherUpdate;
import com.marketplace.pricing.spi.VoucherCreateCommand;
import com.marketplace.pricing.spi.VoucherMutationError;
import com.marketplace.pricing.spi.VoucherMutationErrorCode;
import com.marketplace.pricing.spi.VoucherMutationResult;
import com.marketplace.pricing.spi.VoucherUpdateCommand;
import com.marketplace.pricing.spi.VoucherWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P3 (the canonical Saleor plan §5-P3): the GraphQL adapter for the
 * Saleor Discounts-domain {@code voucherCreate}, {@code voucherUpdate},
 * {@code voucherDelete} and {@code voucherBulkDelete} mutation roots —
 * over the pricing owner's write use cases through the declared
 * {@code VoucherWriteSpi} seam. This class owns ONLY transport mapping:
 * typed global-ID decode, the bounded input-to-command mapping (the
 * generated {@code VoucherInput} binding by name — the long input
 * fields clamp to the command's long semantics) and the domain result
 * onto Saleor's payload shapes. Authorization lives in the owning use
 * case ({@code @PreAuthorize("hasRole('ADMIN')")} — the
 * MANAGE_DISCOUNTS root's platform capability).
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code VoucherMutationError} entries); transport-level validation —
 * a malformed or mistyped global ID, a keyless call — answers the
 * classified VAL-001 GraphQL error family the read roots established.
 * The declared-but-unowned {@code externalReference} argument (no
 * external-ID column) answers the explicit classified error when sent,
 * never a silent no-op.</p>
 */
@DgsComponent
public class VoucherMutationDataFetcher {

    private final VoucherWriteSpi voucherWriteSpi;
    private final VoucherGraphQlMapper voucherMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public VoucherMutationDataFetcher(VoucherWriteSpi voucherWriteSpi,
                                      VoucherGraphQlMapper voucherMapper,
                                      SaleorGlobalIdCodec globalIdCodec) {
        this.voucherWriteSpi = voucherWriteSpi;
        this.voucherMapper = voucherMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code voucherCreate(input: VoucherInput!): VoucherCreate} —
     * the voucher policy write over the owner's use case. The generated
     * input's Int fields clamp to the command's long semantics (Int is
     * the GraphQL 32-bit ceiling; the owner's own bounds reject invalid
     * ranges regardless).
     */
    @DgsMutation(field = DgsConstants.MUTATION.VoucherCreate)
    @RateLimiter(name = "graphqlVoucherMutation")
    public VoucherCreate voucherCreate(@InputArgument VoucherInput input) {
        if (input == null) {
            throw new IllegalArgumentException("voucherCreate requires a non-null input");
        }
        VoucherMutationResult result = voucherWriteSpi.createVoucher(toCreateCommand(input));
        return VoucherCreate.newBuilder()
                .errors(toDiscountErrors(result.errors()))
                .voucher(result.voucher() == null ? null : voucherMapper.toGraphQlVoucher(result.voucher()))
                .build();
    }

    /**
     * Saleor {@code voucherUpdate(externalReference: String, id: ID,
     * input: VoucherInput!): VoucherUpdate} — the policy amendment. The
     * {@code id} argument executes through the typed global-ID codec;
     * the {@code externalReference} argument has no owning column and
     * answers the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.VoucherUpdate)
    @RateLimiter(name = "graphqlVoucherMutation")
    public VoucherUpdate voucherUpdate(@InputArgument String externalReference,
                                       @InputArgument String id,
                                       @InputArgument VoucherInput input) {
        if (input == null) {
            throw new IllegalArgumentException("voucherUpdate requires a non-null input");
        }
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "voucherUpdate externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("voucherUpdate requires the id of the voucher to update");
        }
        UUID nativeId = globalIdCodec.decode(id, VoucherGraphQlMapper.VOUCHER_TYPE);
        VoucherMutationResult result = voucherWriteSpi.updateVoucher(
                nativeId, toUpdateCommand(input));
        return VoucherUpdate.newBuilder()
                .errors(toDiscountErrors(result.errors()))
                .voucher(result.voucher() == null ? null : voucherMapper.toGraphQlVoucher(result.voucher()))
                .build();
    }

    /**
     * Saleor {@code voucherDelete(externalReference: String, id: ID):
     * VoucherDelete} — the policy soft delete; a keyless call answers
     * the classified validation error.
     */
    @DgsMutation(field = DgsConstants.MUTATION.VoucherDelete)
    @RateLimiter(name = "graphqlVoucherMutation")
    public VoucherDelete voucherDelete(@InputArgument String externalReference,
                                       @InputArgument String id) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "voucherDelete externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("voucherDelete requires the id of the voucher to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, VoucherGraphQlMapper.VOUCHER_TYPE);
        VoucherMutationResult result = voucherWriteSpi.deleteVoucher(nativeId);
        return VoucherDelete.newBuilder()
                .errors(toDiscountErrors(result.errors()))
                .voucher(result.voucher() == null ? null : voucherMapper.toGraphQlVoucher(result.voucher()))
                .build();
    }

    /**
     * Saleor {@code voucherBulkDelete(ids: [ID!]!): VoucherBulkDelete} —
     * the policy bulk soft delete; every id decodes through the typed
     * codec and the count rides the owner's honest number.
     */
    @DgsMutation(field = DgsConstants.MUTATION.VoucherBulkDelete)
    @RateLimiter(name = "graphqlVoucherMutation")
    public VoucherBulkDelete voucherBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("voucherBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, VoucherGraphQlMapper.VOUCHER_TYPE));
        }
        return VoucherBulkDelete.newBuilder()
                .count(voucherWriteSpi.deleteVouchers(nativeIds))
                .errors(List.of())
                .build();
    }

    /** The generated input onto the owner's create command. */
    private static VoucherCreateCommand toCreateCommand(VoucherInput input) {
        return new VoucherCreateCommand(
                input.getName(),
                input.getCode(),
                input.getDiscountValueType() == null ? null : input.getDiscountValueType().name(),
                input.getDiscountValue() == null ? null : (long) input.getDiscountValue(),
                input.getCurrency(),
                input.getMinSpend() == null ? null : (long) input.getMinSpend(),
                input.getStartDate(),
                input.getEndDate(),
                input.getUsageLimit() == null ? null : (long) input.getUsageLimit(),
                input.getApplyOncePerCustomer());
    }

    /** The generated input onto the owner's amendment command. */
    private static VoucherUpdateCommand toUpdateCommand(VoucherInput input) {
        return new VoucherUpdateCommand(
                input.getName(),
                input.getCode(),
                input.getDiscountValueType() == null ? null : input.getDiscountValueType().name(),
                input.getDiscountValue() == null ? null : (long) input.getDiscountValue(),
                input.getCurrency(),
                input.getMinSpend() == null ? null : (long) input.getMinSpend(),
                input.getStartDate(),
                input.getEndDate(),
                input.getUsageLimit() == null ? null : (long) input.getUsageLimit(),
                input.getApplyOncePerCustomer());
    }

    /**
     * The domain error entries onto the Saleor payload error objects —
     * the registered ADAPT mapping (the codes are the shared enum
     * members; the field/message ride verbatim).
     */
    private static List<DiscountError> toDiscountErrors(List<VoucherMutationError> errors) {
        List<DiscountError> mapped = new ArrayList<>(errors.size());
        for (VoucherMutationError error : errors) {
            mapped.add(DiscountError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toDiscountErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static DiscountErrorCode toDiscountErrorCode(VoucherMutationErrorCode code) {
        return switch (code) {
            case REQUIRED -> DiscountErrorCode.REQUIRED;
            case INVALID -> DiscountErrorCode.INVALID;
            case NOT_FOUND -> DiscountErrorCode.NOT_FOUND;
            case ALREADY_EXISTS -> DiscountErrorCode.ALREADY_EXISTS;
            case VOUCHER_ALREADY_USED -> DiscountErrorCode.VOUCHER_ALREADY_USED;
        };
    }
}
