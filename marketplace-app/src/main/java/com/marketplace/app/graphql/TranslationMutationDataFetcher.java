package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AttributeTranslate;
import com.marketplace.app.graphql.generated.types.AttributeValueTranslate;
import com.marketplace.app.graphql.generated.types.AttributeValueTranslationInput;
import com.marketplace.app.graphql.generated.types.CategoryTranslate;
import com.marketplace.app.graphql.generated.types.CollectionTranslate;
import com.marketplace.app.graphql.generated.types.LanguageCodeEnum;
import com.marketplace.app.graphql.generated.types.NameTranslationInput;
import com.marketplace.app.graphql.generated.types.TranslationError;
import com.marketplace.app.graphql.generated.types.TranslationErrorCode;
import com.marketplace.app.graphql.generated.types.TranslationInput;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CollectionMutationError;
import com.marketplace.catalog.spi.CollectionMutationResult;
import com.marketplace.catalog.spi.TranslationAmendCommand;
import com.marketplace.catalog.spi.TranslationWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor translation family's {@code categoryTranslate},
 * {@code attributeTranslate} and {@code attributeValueTranslate}
 * mutation roots — over the catalog owner's translation write use
 * cases through the declared {@code TranslationWriteSpi} seam. This
 * class owns ONLY transport mapping: typed global-ID decode, the
 * bounded input-to-command mapping (the addressed language dimension
 * rides the platform's own AR/EN bilingual closure) and the domain
 * result onto Saleor's payload shapes. Authorization lives in the
 * owning use case ({@code @PreAuthorize("hasRole('ADMIN')")} — the
 * MANAGE_TRANSLATIONS root's platform capability), exactly as every
 * write family in this packet established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owning
 * family's error entries onto the {@code TranslationError} vocabulary);
 * transport-level validation — a malformed or mistyped global ID, a
 * keyless call — answers the classified VAL-001 GraphQL error family
 * the read roots established. The language dimension is the generated
 * enum's own closure (AR/EN — the central schema's two-member
 * LanguageCodeEnum); an unknown enum value never reaches this
 * adapter (GraphQL validation rejects it first).</p>
 */
@DgsComponent
public class TranslationMutationDataFetcher {

    private final TranslationWriteSpi translationWriteSpi;
    private final CategoryGraphQlMapper categoryMapper;
    private final AttributeGraphQlMapper attributeMapper;
    private final CollectionGraphQlMapper collectionMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public TranslationMutationDataFetcher(TranslationWriteSpi translationWriteSpi,
                                          CategoryGraphQlMapper categoryMapper,
                                          AttributeGraphQlMapper attributeMapper,
                                          CollectionGraphQlMapper collectionMapper,
                                          SaleorGlobalIdCodec globalIdCodec) {
        this.translationWriteSpi = translationWriteSpi;
        this.categoryMapper = categoryMapper;
        this.attributeMapper = attributeMapper;
        this.collectionMapper = collectionMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code categoryTranslate(id: ID!, input: TranslationInput!,
     * languageCode: LanguageCodeEnum!): CategoryTranslate} — the
     * category registry's bilingual amendment. The {@code id} argument
     * executes through the typed global-ID codec; the bounded input
     * carries the addressed language's display name (null preserves —
     * the omit-to-preserve semantics).
     */
    @DgsMutation(field = DgsConstants.MUTATION.CategoryTranslate)
    @RateLimiter(name = "graphqlCategoryMutation")
    public CategoryTranslate categoryTranslate(@InputArgument String id,
                                               @InputArgument TranslationInput input,
                                               @InputArgument LanguageCodeEnum languageCode) {
        requireInput(input, "categoryTranslate");
        if (id == null) {
            throw new IllegalArgumentException("categoryTranslate requires the id of the category to translate");
        }
        UUID nativeId = globalIdCodec.decode(id, CategoryGraphQlMapper.CATEGORY_TYPE);
        CategoryMutationResult result = translationWriteSpi.translateCategory(
                nativeId, new TranslationAmendCommand(input.getName(), languageCode.name()));
        return CategoryTranslate.newBuilder()
                .errors(toTranslationErrors(mapCategoryErrors(result.errors())))
                .category(result.category() == null
                        ? null : categoryMapper.toGraphQlCategory(result.category()))
                .build();
    }

    /**
     * Saleor {@code attributeTranslate(id: ID!, input:
     * NameTranslationInput!, languageCode: LanguageCodeEnum!):
     * AttributeTranslate} — the attribute registry's bilingual
     * amendment over the same seam.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeTranslate)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeTranslate attributeTranslate(@InputArgument String id,
                                                 @InputArgument NameTranslationInput input,
                                                 @InputArgument LanguageCodeEnum languageCode) {
        requireInput(input, "attributeTranslate");
        if (id == null) {
            throw new IllegalArgumentException("attributeTranslate requires the id of the attribute to translate");
        }
        UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_TYPE);
        AttributeMutationResult result = translationWriteSpi.translateAttribute(
                nativeId, new TranslationAmendCommand(input.getName(), languageCode.name()));
        return AttributeTranslate.newBuilder()
                .errors(toTranslationErrors(mapAttributeErrors(result.errors())))
                .attribute(result.attribute() == null
                        ? null : attributeMapper.toGraphQlAttribute(result.attribute()))
                .build();
    }

    /**
     * Saleor {@code attributeValueTranslate(id: ID!, input:
     * AttributeValueTranslationInput!, languageCode: LanguageCodeEnum!):
     * AttributeValueTranslate} — the CHOICE option registry's bilingual
     * amendment (the payload's own dual projection: the value AND the
     * owning attribute).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeValueTranslate)
    @RateLimiter(name = "graphqlAttributeValueMutation")
    public AttributeValueTranslate attributeValueTranslate(@InputArgument String id,
                                                           @InputArgument AttributeValueTranslationInput input,
                                                           @InputArgument LanguageCodeEnum languageCode) {
        requireInput(input, "attributeValueTranslate");
        if (id == null) {
            throw new IllegalArgumentException(
                    "attributeValueTranslate requires the id of the attribute value to translate");
        }
        UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_VALUE_TYPE);
        AttributeValueMutationResult result = translationWriteSpi.translateAttributeValue(
                nativeId, new TranslationAmendCommand(input.getName(), languageCode.name()));
        return AttributeValueTranslate.newBuilder()
                .errors(toTranslationErrors(mapAttributeErrors(result.errors())))
                .attributeValue(result.value() == null
                        ? null : attributeMapper.toGraphQlAttributeValue(result.value()))
                .build();
    }

    /**
     * Saleor {@code collectionTranslate(id: ID!, input: TranslationInput!,
     * languageCode: LanguageCodeEnum!): CollectionTranslate} — the
     * storefront collection registry's bilingual amendment over the same
     * seam (the agent-2 L-slice).
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionTranslate)
    @RateLimiter(name = "graphqlCollectionMutation")
    public CollectionTranslate collectionTranslate(@InputArgument String id,
                                                    @InputArgument TranslationInput input,
                                                    @InputArgument LanguageCodeEnum languageCode) {
        requireInput(input, "collectionTranslate");
        if (id == null) {
            throw new IllegalArgumentException("collectionTranslate requires the id of the collection to translate");
        }
        UUID nativeId = globalIdCodec.decode(id, CollectionGraphQlMapper.COLLECTION_TYPE);
        CollectionMutationResult result = translationWriteSpi.translateCollection(
                nativeId, new TranslationAmendCommand(input.getName(), languageCode.name()));
        return CollectionTranslate.newBuilder()
                .errors(toTranslationErrors(mapCollectionErrors(result.errors())))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * The input presence guard — the SDL's own {@code input: ...!}
     * contract mirrored at the boundary (a null input is a client error,
     * never a NullPointerException).
     */
    private static void requireInput(Object input, String rootName) {
        if (input == null) {
            throw new IllegalArgumentException(rootName + " requires a non-null input");
        }
    }

    /**
     * The owning family's domain error entries onto the shared
     * (field, message, code) findings — the per-family codes map onto
     * the TranslationError vocabulary's own members.
     */
    private record TransportError(String field, String message, TranslationErrorCode code) {
    }

    private static List<TransportError> mapCategoryErrors(List<CategoryMutationError> errors) {
        List<TransportError> mapped = new ArrayList<>(errors.size());
        for (CategoryMutationError error : errors) {
            mapped.add(new TransportError(error.field(), error.message(),
                    switch (error.code()) {
                        case REQUIRED -> TranslationErrorCode.REQUIRED;
                        case INVALID -> TranslationErrorCode.INVALID;
                        case NOT_FOUND -> TranslationErrorCode.NOT_FOUND;
                        case ALREADY_EXISTS -> TranslationErrorCode.UNIQUE;
                    }));
        }
        return mapped;
    }

    private static List<TransportError> mapAttributeErrors(List<AttributeMutationError> errors) {
        List<TransportError> mapped = new ArrayList<>(errors.size());
        for (AttributeMutationError error : errors) {
            mapped.add(new TransportError(error.field(), error.message(),
                    switch (error.code()) {
                        case REQUIRED -> TranslationErrorCode.REQUIRED;
                        case INVALID -> TranslationErrorCode.INVALID;
                        case NOT_FOUND -> TranslationErrorCode.NOT_FOUND;
                        case ALREADY_EXISTS -> TranslationErrorCode.UNIQUE;
                    }));
        }
        return mapped;
    }

    private static List<TransportError> mapCollectionErrors(List<CollectionMutationError> errors) {
        List<TransportError> mapped = new ArrayList<>(errors.size());
        for (CollectionMutationError error : errors) {
            mapped.add(new TransportError(error.field(), error.message(),
                    switch (error.code()) {
                        case REQUIRED -> TranslationErrorCode.REQUIRED;
                        case INVALID, GRAPHQL_ERROR, DUPLICATED_INPUT_ITEM ->
                                TranslationErrorCode.INVALID;
                        case NOT_FOUND -> TranslationErrorCode.NOT_FOUND;
                        case UNIQUE -> TranslationErrorCode.UNIQUE;
                    }));
        }
        return mapped;
    }

    /**
     * The (field, message, code) findings onto the Saleor payload error
     * objects — the registered ADAPT mapping (the codes are the shared
     * enum members; the field/message ride verbatim).
     */
    private static List<TranslationError> toTranslationErrors(List<TransportError> errors) {
        List<TranslationError> mapped = new ArrayList<>(errors.size());
        for (TransportError error : errors) {
            mapped.add(TranslationError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(error.code())
                    .build());
        }
        return mapped;
    }
}
