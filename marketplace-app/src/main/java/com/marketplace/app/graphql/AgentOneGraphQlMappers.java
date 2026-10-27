package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.AccountError;
import com.marketplace.app.graphql.generated.types.AccountErrorCode;
import com.marketplace.app.graphql.generated.types.Address;
import com.marketplace.app.graphql.generated.types.CustomerType;
import com.marketplace.app.graphql.generated.types.CustomerTypeCountableEdge;
import com.marketplace.app.graphql.generated.types.CustomerTypeCountableConnection;
import com.marketplace.app.graphql.generated.types.MenuError;
import com.marketplace.app.graphql.generated.types.MenuErrorCode;
import com.marketplace.app.graphql.generated.types.PageError;
import com.marketplace.app.graphql.generated.types.PageErrorCode;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.ShopError;
import com.marketplace.app.graphql.generated.types.ShopErrorCode;
import com.marketplace.app.graphql.generated.types.StaffError;
import com.marketplace.identity.spi.AddressView;
import com.marketplace.identity.spi.CustomerTypeView;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * shared transport mappers of this packet's Users-domain surfaces — one
 * wire projection per entity (the UserGraphQlMapper discipline: the
 * mapper owns ONLY the typed global-ID encode and the wire shape, never
 * a business rule), plus the payload error builders every fetcher rides.
 */
@Component
class AgentOneGraphQlMappers {

    static final String ADDRESS_TYPE = "Address";
    static final String CUSTOMER_TYPE_TYPE = "CustomerType";

    private final SaleorGlobalIdCodec globalIdCodec;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    AgentOneGraphQlMappers(SaleorGlobalIdCodec globalIdCodec,
                           EncodingCursorStrategy<ScrollPosition> cursorStrategy) {
        this.globalIdCodec = globalIdCodec;
        this.cursorStrategy = cursorStrategy;
    }

    // ---------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------

    Address toGraphQlAddress(AddressView view) {
        return Address.newBuilder()
                .id(globalIdCodec.encode(ADDRESS_TYPE, view.id()))
                .firstName(nullToEmpty(view.firstName()))
                .lastName(nullToEmpty(view.lastName()))
                .companyName(nullToEmpty(view.companyName()))
                .streetAddress1(nullToEmpty(view.streetAddress1()))
                .streetAddress2(nullToEmpty(view.streetAddress2()))
                .city(nullToEmpty(view.city()))
                .cityArea(nullToEmpty(view.cityArea()))
                .postalCode(nullToEmpty(view.postalCode()))
                .country(com.marketplace.app.graphql.generated.types.CountryDisplay.newBuilder()
                        .code(view.country() == null ? null
                                : com.marketplace.app.graphql.generated.types.CountryCode
                                        .valueOf(view.country()))
                        .country(countryName(view.country()))
                        .build())
                .countryArea(nullToEmpty(view.countryArea()))
                .phone(view.phone())
                .isDefaultShippingAddress(view.defaultShipping())
                .isDefaultBillingAddress(view.defaultBilling())
                .build();
    }

    AddressView toAddressView(com.marketplace.app.graphql.generated.types.AddressInput input) {
        return new AddressView(null, null,
                input.getFirstName(), input.getLastName(), input.getCompanyName(),
                input.getStreetAddress1(), input.getStreetAddress2(),
                input.getCity(), input.getCityArea(), input.getPostalCode(),
                input.getCountry() == null ? null : input.getCountry().name(),
                input.getCountryArea(), input.getPhone(), false, false);
    }

    CustomerType toGraphQlCustomerType(CustomerTypeView view) {
        List<com.marketplace.app.graphql.generated.types.Attribute> attributes = null;
        return CustomerType.newBuilder()
                .id(globalIdCodec.encode(CUSTOMER_TYPE_TYPE, view.id()))
                .name(view.name())
                .slug(view.slug())
                .isDefault(view.defaultType())
                .attributes(attributes)
                .build();
    }

    com.marketplace.app.graphql.generated.types.StaffNotificationRecipient
    toGraphQlRecipient(com.marketplace.identity.spi.RecipientView view) {
        return com.marketplace.app.graphql.generated.types.StaffNotificationRecipient.newBuilder()
                .id(globalIdCodec.encode("StaffNotificationRecipient", view.id()))
                .user(null)
                .email(view.email())
                .active(view.active())
                .build();
    }

    // ---------------------------------------------------------------------
    // Connections
    // ---------------------------------------------------------------------

    <T> CustomerTypeCountableConnection toCustomerTypeConnection(Window<CustomerTypeView> window,
                                                                  long total) {
        List<CustomerTypeCountableEdge> edges = new ArrayList<>(window.getContent().size());
        for (int i = 0; i < window.getContent().size(); i++) {
            edges.add(CustomerTypeCountableEdge.newBuilder()
                    .node(toGraphQlCustomerType(window.getContent().get(i)))
                    .cursor(cursorStrategy.toCursor(window.positionAt(i)))
                    .build());
        }
        return CustomerTypeCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(PageInfo.newBuilder()
                        .hasNextPage(window.hasNext())
                        .hasPreviousPage(false)
                        .startCursor(edges.isEmpty() ? null : edges.get(0).getCursor())
                        .endCursor(edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor())
                        .build())
                .totalCount((int) Math.min(total, Integer.MAX_VALUE))
                .build();
    }

    // ---------------------------------------------------------------------
    // Payload error builders (the frozen §5 wire taxonomy)
    // ---------------------------------------------------------------------

    List<AccountError> accountErrors(Throwable failure) {
        return List.of(AccountError.newBuilder()
                .field(null).message(SaleorMutationErrors.of(failure).message())
                .code(AccountErrorCode.valueOf(SaleorMutationErrors.codeOf(failure)))
                .build());
    }

    List<AccountError> emptyAccountErrors() {
        return List.of();
    }

    List<StaffError> staffErrors(Throwable failure) {
        return List.of(StaffError.newBuilder()
                .field(null).message(SaleorMutationErrors.of(failure).message())
                .code(AccountErrorCode.valueOf(SaleorMutationErrors.codeOf(failure)))
                .build());
    }

    List<StaffError> emptyStaffErrors() {
        return List.of();
    }

    List<ShopError> shopErrors(Throwable failure) {
        return List.of(ShopError.newBuilder()
                .field(null).message(SaleorMutationErrors.of(failure).message())
                .code(ShopErrorCode.valueOf(SaleorMutationErrors.codeOf(failure)))
                .build());
    }

    List<ShopError> emptyShopErrors() {
        return List.of();
    }

    List<PageError> pageErrors(Throwable failure) {
        return List.of(PageError.newBuilder()
                .field(null).message(SaleorMutationErrors.of(failure).message())
                .code(PageErrorCode.valueOf(SaleorMutationErrors.codeOf(failure)))
                .build());
    }

    List<PageError> emptyPageErrors() {
        return List.of();
    }

    List<MenuError> menuErrors(Throwable failure) {
        return List.of(MenuError.newBuilder()
                .field(null).message(SaleorMutationErrors.of(failure).message())
                .code(MenuErrorCode.valueOf(SaleorMutationErrors.codeOf(failure)))
                .build());
    }

    List<MenuError> emptyMenuErrors() {
        return List.of();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String countryName(String code) {
        return new Locale("", code == null ? "" : code).getDisplayCountry(Locale.ENGLISH);
    }
}
