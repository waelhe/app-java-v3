package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.StoreProduct;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeValue;
import com.marketplace.app.graphql.generated.types.StoreProductType;
import com.marketplace.app.graphql.generated.types.StoreProductVariant;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * GraphQL adapter for the provider-owned goods administration journey.
 * It delegates to ProductAdminSpi so REST and GraphQL share the catalog's
 * secured use cases. This class owns only cursor/transport mapping and
 * boundary validation, never catalog business rules or persistence.
 */
@DgsComponent
public class StoreProductDataFetcher {

    private final ProductAdminSpi productAdminSpi;
    private final StoreProductGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final Validator validator;

    public StoreProductDataFetcher(ProductAdminSpi productAdminSpi,
                                   StoreProductGraphQlMapper mapper,
                                   GraphQlProperties properties,
                                   EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                   Validator validator) {
        this.productAdminSpi = productAdminSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.validator = validator;
    }

    @DgsQuery(field = DgsConstants.QUERY.StoreProduct)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public StoreProduct storeProduct(@InputArgument UUID id) {
        return mapper.toResponse(productAdminSpi.getOwnedProduct(id, currentAuthentication()));
    }

    @DgsQuery(field = DgsConstants.QUERY.StoreProducts)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public Window<StoreProduct> storeProducts(@InputArgument Integer first, @InputArgument String after) {
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return Window.from(List.of(), index -> ScrollPosition.keyset(), false);
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        return productAdminSpi.scrollOwnedProducts(
                        currentAuthentication(), keysetPosition(after), Limit.of(pageSize))
                .map(mapper::toResponse);
    }

    @DgsQuery(field = DgsConstants.QUERY.StoreProductTypes)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<StoreProductType> storeProductTypes() {
        return productAdminSpi.listProductTypes().stream().map(mapper::toResponse).toList();
    }

    @DgsQuery(field = DgsConstants.QUERY.StoreProductAttributeValues)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<StoreProductAttributeValue> storeProductAttributeValues(@InputArgument UUID productId) {
        return productAdminSpi.listProductAttributeValues(productId, currentAuthentication())
                .stream().map(mapper::toResponse).toList();
    }

    @DgsQuery(field = DgsConstants.QUERY.StoreProductVariants)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<StoreProductVariant> storeProductVariants(@InputArgument UUID productId) {
        return productAdminSpi.listProductVariants(productId, currentAuthentication())
                .stream().map(mapper::toResponse).toList();
    }

    @DgsQuery(field = DgsConstants.QUERY.StoreProductVariantAttributeValues)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<StoreProductAttributeValue> storeProductVariantAttributeValues(@InputArgument UUID variantId) {
        return productAdminSpi.listVariantAttributeValues(variantId, currentAuthentication())
                .stream().map(mapper::toResponse).toList();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CreateStoreProduct)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public StoreProduct createStoreProduct(@InputArgument StoreProductCreateInput input) {
        validate(input);
        long priceMinor = parsePriceMinor(input.priceMinor());
        ProductAdminSpi.CreateProductCommand command = new ProductAdminSpi.CreateProductCommand(
                input.storeCategoryCode(), input.title(), input.description(), priceMinor,
                input.currency(), input.productTypeId());
        return mapper.toResponse(productAdminSpi.createProduct(command, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.UpdateStoreProduct)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public StoreProduct updateStoreProduct(@InputArgument UUID id,
                                           @InputArgument StoreProductUpdateInput input) {
        validate(input);
        long priceMinor = parsePriceMinor(input.priceMinor());
        ProductAdminSpi.UpdateProductCommand command =
                new ProductAdminSpi.UpdateProductCommand(input.title(), input.description(), priceMinor);
        return mapper.toResponse(productAdminSpi.updateProduct(id, command, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.PublishStoreProduct)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public StoreProduct publishStoreProduct(@InputArgument UUID id) {
        return mapper.toResponse(productAdminSpi.publishProduct(id, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.ReplaceStoreProductAttributes)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public List<StoreProductAttributeValue> replaceStoreProductAttributes(
            @InputArgument UUID id, @InputArgument List<StoreProductAttributeValueInput> values) {
        List<StoreProductAttributeValueInput> safeValues = values == null ? List.of() : values;
        safeValues.forEach(this::validateAttributeValue);
        return productAdminSpi.replaceProductAttributeValues(id, toSpiValues(safeValues), currentAuthentication())
                .stream().map(mapper::toResponse).toList();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CreateStoreProductVariant)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public StoreProductVariant createStoreProductVariant(
            @InputArgument UUID productId, @InputArgument StoreProductVariantCreateInput input) {
        validate(input);
        input.attributeValues().forEach(this::validateAttributeValue);
        ProductAdminSpi.CreateProductVariantCommand command = new ProductAdminSpi.CreateProductVariantCommand(
                input.sku(), input.name(), parsePriceMinor(input.priceMinor()), toSpiValues(input.attributeValues()));
        return mapper.toResponse(productAdminSpi.createProductVariant(productId, command, currentAuthentication()));
    }

    private void validate(Object input) {
        Set<? extends ConstraintViolation<?>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void validateAttributeValue(StoreProductAttributeValueInput input) {
        validate(input);
        if (input.numberValue() != null) {
            try {
                new BigDecimal(input.numberValue());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("numberValue must be a valid base-10 decimal", exception);
            }
        }
    }

    private static List<ProductAdminSpi.AttributeValueInput> toSpiValues(
            List<StoreProductAttributeValueInput> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> new ProductAdminSpi.AttributeValueInput(
                value.assignmentId(), value.textValue(), value.numberValue(),
                value.booleanValue(), value.optionId())).toList();
    }

    private static long parsePriceMinor(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "priceMinor must be a non-negative decimal integer within the 64-bit range", exception);
        }
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for catalog administration");
        }
        return authentication;
    }

    private KeysetScrollPosition keysetPosition(String after) {
        if (after == null) {
            return ScrollPosition.keyset();
        }

        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, "storeProducts");
    }
}
