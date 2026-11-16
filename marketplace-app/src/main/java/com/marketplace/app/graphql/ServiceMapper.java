package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Service;
import com.marketplace.app.graphql.generated.types.ServiceStatus;
import com.marketplace.shared.api.ProviderListingView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

/**
 * Maps the catalog module's shared SPI view into the GraphQL surface's
 * transport contract — the DGS CodeGen-generated {@code types.Service}
 * data type (target design §11 Phase 3.3/3.4: generated transport types
 * are the contract at the adapter boundary; the hand-written
 * ServiceResponse record was replaced, not paralleled).
 *
 * <p>The mappings are mechanical (the plan's MapStruct allowance): the
 * display-unit price conversion from the domain's integer cents, the
 * {@code title} to {@code name} rename the SDL fixed long ago, the
 * {@code ID} string rendering, and the domain status vocabulary into
 * the schema's enum values. Value constraints live where the plan puts
 * them: in the validated input record and the owning use case — never
 * in this projection.</p>
 */
@Mapper(componentModel = "spring")
public interface ServiceMapper {

    /**
     * The shared catalog view into the generated transport contract:
     * the display-unit price expression and the {@code title} to
     * {@code name} rename are declared here; the {@code id} and
     * {@code status} legs run through the documented default-method
     * conversions below; {@code description}, {@code category} and
     * {@code currency} are same-name pass-throughs.
     */
    @Mapping(target = "name", source = "title")
    @Mapping(target = "price", expression = "java(listing.priceCents() != null ? listing.priceCents().doubleValue() / 100.0 : 0.0)")
    Service toResponse(ProviderListingView listing);

    /**
     * The {@code ID!} transport rendering: the codegen's documented
     * scalar mapping generates the SDL {@code ID} field as
     * {@code String} (measured from the graphql-dgs-codegen 8.6.0
     * TypeUtils), and graphql-java's GraphQLID coerce-serializes any
     * object through {@code toString()} — the UUID the hand-written
     * record carried and this string land identically on the wire.
     */
    default String toTransportId(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * The domain status vocabulary into the schema's enum: the shared
     * view's {@code ACTIVE} string is the schema's {@code ACTIVE}
     * value; every other domain state (draft, paused, expired, ...) is
     * the surface's long-standing INACTIVE projection — the same
     * mapping the hand-written record's string field performed, now
     * typed by the generated enum the schema itself declares.
     */
    default ServiceStatus toTransportStatus(String status) {
        return "ACTIVE".equals(status) ? ServiceStatus.ACTIVE : ServiceStatus.INACTIVE;
    }
}
