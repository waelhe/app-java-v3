package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.DiscountValueTypeEnum;
import com.marketplace.app.graphql.generated.types.Voucher;
import com.marketplace.pricing.spi.VoucherView;
import org.springframework.stereotype.Component;

/**
 * P3 (the canonical Saleor plan §5-P3): the transport mapping of the
 * pricing owner's bounded voucher projection onto the Saleor-shaped
 * {@code Voucher} wire type. This class owns ONLY transport mapping —
 * the global-ID encode and the ISO-8601 instant rendering — never a
 * business rule (the same boundary discipline every house mapper
 * follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * native {@code vouchers} key behind it. The declared closure is
 * exactly the bounded projection's own facts: {@code name}/{@code code}
 * (the policy row's own), {@code usageLimit} (the global redemption
 * limit) and {@code used} (the redemption-row count — the owner's own
 * accounting seam; Int's signed 32-bit ceiling documented, hence the
 * clamp), {@code applyOncePerCustomer} (the truthful flattening of the
 * per-user limit), {@code discountValueType} (the owner's PERCENTAGE/
 * FIXED vocabulary rendered verbatim), {@code currency} (the policy
 * row's own ISO-4217 denomination) and the window instants rendered as
 * ISO-8601 strings (the house no-new-scalar decision — Saleor's
 * DateTime scalar divergence row). Unowned Saleor fields — the
 * discountValue/minSpent money surfaces (the frozen money contract
 * forbids binary float for authoritative amounts), the voucher-type
 * discrimination, the codes/single-use feature-preview model, the
 * catalogue scoping, channel listings, the metadata family and
 * translations — stay undeclared until an owning capability exists:
 * never faked.</p>
 */
@Component
public class VoucherGraphQlMapper {

    /** The GraphQL type tag of the Saleor Voucher type — the codec's own contract. */
    static final String VOUCHER_TYPE = "Voucher";

    private final SaleorGlobalIdCodec globalIdCodec;

    public VoucherGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one voucher projection onto the wire type.
     *
     * @param view the pricing owner's bounded voucher projection
     * @return the Saleor-shaped voucher projection
     */
    public Voucher toGraphQlVoucher(VoucherView view) {
        return Voucher.newBuilder()
                .id(globalIdCodec.encode(VOUCHER_TYPE, view.id()))
                .name(view.name())
                .code(view.code())
                .usageLimit(view.usageLimit() == null
                        ? null
                        : (int) Math.min(view.usageLimit(), Integer.MAX_VALUE))
                .used((int) Math.min(view.timesUsed(), Integer.MAX_VALUE))
                .startDate(view.startsAt() == null ? null : view.startsAt().toString())
                .endDate(view.endsAt() == null ? null : view.endsAt().toString())
                .discountValueType(view.discountValueType() == VoucherView.DiscountType.PERCENTAGE
                        ? DiscountValueTypeEnum.PERCENTAGE
                        : DiscountValueTypeEnum.FIXED)
                .currency(view.currency())
                .applyOncePerCustomer(view.applyOncePerCustomer())
                .build();
    }
}
