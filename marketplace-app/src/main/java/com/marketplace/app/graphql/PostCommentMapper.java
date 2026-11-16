package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.PostComment;
import com.marketplace.shared.api.NeighborhoodPostCommentView;
import org.mapstruct.Mapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps the community module's shared comment projection into the
 * GraphQL surface's transport contract — the DGS CodeGen-generated
 * data types the authoritative SDL derives (the
 * {@code NeighborhoodFeedMapper}'s own discipline verbatim: the
 * generated type's field set IS the schema contract; nothing is
 * invented, nothing dropped — the same-journey field set the REST
 * comments read view carries).
 *
 * <p>The mappings are mechanical (the plan's MapStruct allowance): the
 * {@code ID} string rendering and the ISO-8601 instant rendering — the
 * same deliberate no-new-scalar decision every temporal field on this
 * surface carries (a typed DateTime scalar stays a measured-need
 * decision, never a silent drift).</p>
 */
@Mapper(componentModel = "spring")
public interface PostCommentMapper {

    /** The shared comment view into the generated transport contract. */
    PostComment toTransport(NeighborhoodPostCommentView view);

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
     * deliberate no-new-scalar decision the SDL documents.
     */
    default String toTransportInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
