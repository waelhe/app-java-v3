package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.DiscoveryCard;
import com.marketplace.app.graphql.generated.types.DiscoveryRail;
import com.marketplace.app.graphql.generated.types.DiscoveryRow;
import com.marketplace.app.graphql.generated.types.DiscoverySource;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.DiscoveryRowType;
import com.marketplace.shared.api.DiscoveryRowView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps the discovery module's shared projection into the GraphQL
 * surface's transport contract — the DGS CodeGen-generated data types
 * the authoritative SDL derives (target design §11 Phase 3.3/3.4: the
 * generated types ARE the contract at the adapter boundary; the
 * hand-written twin was replaced, never paralleled — this mapper adds
 * nothing the SDL does not declare).
 *
 * <p>The mappings are mechanical (the plan's MapStruct allowance): the
 * {@code ID} string rendering, the ISO-8601 instant rendering, the
 * domain rail enum into the schema's mirror, the closed source-type
 * vocabulary into the schema's mirror, and the scoped per-rail count
 * into the schema's documented Int bound (fail-fast on overflow —
 * never a silent narrowing cast). Value constraints and eligibility
 * rules live where the plan puts them: inside the owning use case —
 * never in this projection.</p>
 */
@Mapper(componentModel = "spring")
public interface DiscoveryMapper {

    /**
     * The shared rail view into the generated transport contract: the
     * rail enum and the cards map through their own legs below, and
     * {@code totalEligible} rides the documented count conversion.
     */
    @Mapping(target = "totalEligible", source = "totalEligible")
    DiscoveryRow toTransport(DiscoveryRowView row);

    /** The shared card view into the generated transport contract. */
    DiscoveryCard toTransport(DiscoveryCardView card);

    /**
     * The domain rail vocabulary into the schema's mirror — strict by
     * name (the SDL and the domain enum carry the same closed set; the
     * codegen test pins the mirror, and a drift fails loudly here,
     * never silently).
     */
    default DiscoveryRail toTransportRail(DiscoveryRowType row) {
        return row == null ? null : DiscoveryRail.valueOf(row.name());
    }

    /**
     * The closed source-type vocabulary into the schema's mirror —
     * strict by name for the same reason: an unknown source type is a
     * contract drift that must fail loudly, never render as a lie.
     */
    default DiscoverySource toTransportSource(String sourceType) {
        return sourceType == null ? null : DiscoverySource.valueOf(sourceType);
    }

    /**
     * The {@code ID!} transport rendering — graphql-java's GraphQLID
     * serializes any object through {@code toString()}, so the UUID and
     * its string land identically on the wire.
     */
    default String toTransportId(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * The ISO-8601 instant rendering (java.time.Instant.toString) — the
     * deliberate no-new-scalar decision the SDL documents: the GraphQL
     * spec has no temporal scalar and this surface adds none to the
     * locked version chain.
     */
    default String toTransportInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * The scoped per-rail count into the schema's documented Int bound —
     * fail-fast arithmetic (Math.toIntExact) on overflow: the SDL
     * documents the signed 32-bit ceiling, and a count that could not
     * honestly fit is a loud error, never a silent narrowing cast.
     */
    default int toTransportCount(long count) {
        return Math.toIntExact(count);
    }
}
