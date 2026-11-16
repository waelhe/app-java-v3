package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.LostFoundState;
import com.marketplace.app.graphql.generated.types.NeighborhoodPost;
import com.marketplace.app.graphql.generated.types.NeighborhoodPostMedia;
import com.marketplace.app.graphql.generated.types.PostCategory;
import com.marketplace.app.graphql.generated.types.PostStatus;
import com.marketplace.shared.api.NeighborhoodFeedMediaView;
import com.marketplace.shared.api.NeighborhoodFeedPostView;
import org.mapstruct.Mapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps the community module's shared feed projection into the GraphQL
 * surface's transport contract — the DGS CodeGen-generated data types
 * the authoritative SDL derives (the ServiceMapper's own discipline:
 * the generated type's field set IS the schema contract; nothing is
 * invented, nothing dropped — the same-journey field set the REST feed
 * view carries).
 *
 * <p>The mappings are mechanical (the plan's MapStruct allowance): the
 * {@code ID} string rendering, the ISO-8601 instant rendering, the
 * domain's closed vocabularies (category, moderation status,
 * lost-and-found state) into the schema's typed mirrors — strict by
 * name: the SDL mirrors the domain enums exactly, the codegen test
 * pins the mirror, and a drift fails loudly at this seam, never
 * renders as a lie — and the live reaction count into the schema's
 * documented Int bound (fail-fast on overflow, never a silent
 * narrowing cast). The boolean legs (declaredCommercial, reactedByMe)
 * and the string legs (title, body, snippet-shaped none here) pass
 * through by name.</p>
 */
@Mapper(componentModel = "spring")
public interface NeighborhoodFeedMapper {

    /** The shared feed view into the generated transport contract. */
    NeighborhoodPost toTransport(NeighborhoodFeedPostView view);

    /** The shared media view into the generated transport contract. */
    NeighborhoodPostMedia toTransport(NeighborhoodFeedMediaView media);

    /**
     * The domain category vocabulary into the schema's mirror — strict
     * by name (an unknown category is a contract drift that must fail
     * loudly, never render as a wrong enum).
     */
    default PostCategory toTransportCategory(String category) {
        return category == null ? null : PostCategory.valueOf(category);
    }

    /**
     * The domain moderation status into the schema's mirror — strict by
     * name for the same reason.
     */
    default PostStatus toTransportStatus(String status) {
        return status == null ? null : PostStatus.valueOf(status);
    }

    /**
     * The lost-and-found state into the schema's mirror — nullable by
     * contract (present ONLY on LOST_FOUND posts, V171's own shape).
     */
    default LostFoundState toTransportLostFoundState(String state) {
        return state == null ? null : LostFoundState.valueOf(state);
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
     * deliberate no-new-scalar decision the SDL documents.
     */
    default String toTransportInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * The live reaction count into the schema's documented Int bound —
     * fail-fast arithmetic on overflow: a count that could not honestly
     * fit is a loud error, never a silent narrowing cast.
     */
    default int toTransportCount(long count) {
        return Math.toIntExact(count);
    }
}
