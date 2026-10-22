package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.CheckoutSpi;
import com.marketplace.orders.spi.CheckoutView;
import com.marketplace.orders.spi.OrderBulkCancelResult;
import com.marketplace.orders.spi.OrderPage;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.orders.spi.OrderSort;
import com.marketplace.orders.spi.OrderView;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = OrderDataFetcherDgsTest.Slice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class OrderDataFetcherDgsTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class Slice {}
    @Autowired DgsQueryExecutor dgs;
    @Autowired EncodingCursorStrategy<ScrollPosition> cursors;
    @MockitoBean CatalogSpi catalog;
    @MockitoBean ProductAdminSpi productAdmin;
    @MockitoBean ProductTypeReadSpi productTypes;
    @MockitoBean AttributeReadSpi attributes;
    @MockitoBean CategoryReadSpi categories;
    @MockitoBean ProductReadSpi products;
    @MockitoBean ProductVariantReadSpi variants;
    @MockitoBean OrderReadSpi orders;
    @MockitoBean DiscoverySpi discovery;
    @MockitoBean NeighborhoodFeedPort neighborhoodFeed;
    @MockitoBean NeighborhoodPostDetailPort postDetail;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean IdentitySpi identity;
    @MockitoBean com.marketplace.orders.spi.OrderCommandSpi orderCommands;
    @MockitoBean CheckoutSpi checkouts;
    @MockitoBean DiscountReadSpi discounts;

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    private static void auth(String subject,String... roles) {
        var authorities=java.util.Arrays.stream(roles).map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(subject,"n/a",authorities));
    }
    private static OrderView view(UUID id,Instant date,String status){return new OrderView(id,date,status);}
    private static String gid(String type,UUID id){return java.util.Base64.getEncoder().encodeToString(
            (type+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static CheckoutView checkout(UUID cart,UUID line,UUID variant){return new CheckoutView(
            cart,List.of(new CheckoutView.Line(line,variant,2)),2);}
    private static ProductVariantView variant(UUID id,UUID product){return new ProductVariantView(
            id,"Default","SKU-1","ACTIVE",new ProductView(product,"Checkout Product",null,"ACTIVE"));}

    @Test void ordersSerializeTheRealConnectionAndKeysetCursor() {
        auth("admin","ADMIN"); UUID id=UUID.randomUUID(); Instant date=Instant.parse("2026-10-20T09:30:00Z");
        when(orders.scrollOrders(any(Authentication.class),any(OrderSort.class),any(),any(Limit.class)))
                .thenReturn(new OrderPage(Window.from(List.of(view(id,date,"PLACED")),
                        i->ScrollPosition.forward(Map.of("placedAt",date,"id",id)),true),9));
        Map<String,Object> conn=dgs.executeAndExtractJsonPath(
                "query{orders(first:1){edges{node{id created status} cursor} totalCount}}","data.orders");
        assertThat(conn.get("totalCount")).isEqualTo(9);
        @SuppressWarnings("unchecked") List<Map<String,Object>> edges=(List<Map<String,Object>>)conn.get("edges");
        assertThat(edges).hasSize(1).first().containsEntry("cursor",
                cursors.toCursor(ScrollPosition.forward(Map.of("placedAt",date,"id",id))));
        @SuppressWarnings("unchecked") Map<String,Object> node=(Map<String,Object>)edges.get(0).get("node");
        assertThat(node).containsEntry("id",gid("Order",id)).containsEntry("created","2026-10-20T09:30:00Z")
                .containsEntry("status","UNCONFIRMED");
    }

    @Test void orderLookupUsesTypedIdAndLifecycleWireNames() {
        auth("admin","ADMIN"); UUID id=UUID.randomUUID(); Instant date=Instant.parse("2026-10-19T08:15:00Z");
        when(orders.findOrderById(eq(id),any(Authentication.class))).thenReturn(Optional.of(view(id,date,"CONFIRMED")));
        Map<String,Object> result=dgs.executeAndExtractJsonPath(
                "query($id:ID!){order(id:$id){id created status}}","data.order",Map.of("id",gid("Order",id)));
        assertThat(result).containsEntry("id",gid("Order",id)).containsEntry("created","2026-10-19T08:15:00Z")
                .containsEntry("status","UNFULFILLED");
        for(String[] pair:new String[][]{{"PLACED","UNCONFIRMED"},{"CONFIRMED","UNFULFILLED"},
                {"FULFILLED","FULFILLED"},{"CANCELLED","CANCELED"}}) {
            when(orders.findOrderById(eq(id),any(Authentication.class))).thenReturn(Optional.of(view(id,date,pair[0])));
            Map<String,Object> state=dgs.executeAndExtractJsonPath(
                    "query($id:ID!){order(id:$id){status}}","data.order",Map.of("id",gid("Order",id)));
            assertThat(state.get("status")).isEqualTo(pair[1]);
        }
    }

    @Test void orderCancelUsesOwnerAndSaleorErrorsPayload() {
        auth("admin","ADMIN"); UUID id=UUID.randomUUID(); Instant date=Instant.parse("2026-10-20T12:00:00Z");
        when(orderCommands.cancelAsAdmin(id)).thenReturn(view(id,date,"CANCELLED"));
        Map<String,Object> result=dgs.executeAndExtractJsonPath(
                "mutation($id:ID!){orderCancel(id:$id){order{id created status} errors{field message code}}}",
                "data.orderCancel",Map.of("id",gid("Order",id)));
        assertThat(result.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked") Map<String,Object> order=(Map<String,Object>)result.get("order");
        assertThat(order).containsEntry("id",gid("Order",id)).containsEntry("status","CANCELED");
        verify(orderCommands).cancelAsAdmin(id);
    }

    @Test void orderBulkCancelUsesOwnerAndCountPayload() {
        auth("admin","ADMIN"); UUID id=UUID.randomUUID();
        when(orderCommands.bulkCancelAsAdmin(List.of(id))).thenReturn(new OrderBulkCancelResult(1,List.of()));
        Map<String,Object> result=dgs.executeAndExtractJsonPath(
                "mutation($ids:[ID!]!){orderBulkCancel(ids:$ids){count errors{field code}}}",
                "data.orderBulkCancel",Map.of("ids",List.of(gid("Order",id))));
        assertThat(result.get("count")).isEqualTo(1); assertThat(result.get("errors")).isEqualTo(List.of());
        verify(orderCommands).bulkCancelAsAdmin(List.of(id));
    }

    @Test void sortAliasesAndDefaultsReachTheOwningReadService() {
        auth("admin","ADMIN"); ArgumentCaptor<OrderSort> sort=ArgumentCaptor.forClass(OrderSort.class);
        when(orders.scrollOrders(any(Authentication.class),sort.capture(),any(),any(Limit.class)))
                .thenReturn(new OrderPage(Window.from(List.<OrderView>of(),i->ScrollPosition.keyset(),false),0));
        dgs.executeAndExtractJsonPath("query{orders(first:2,sortBy:{direction:ASC,field:CREATED_AT}){totalCount}}","data.orders");
        assertThat(sort.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.CREATED_AT,true));
        dgs.executeAndExtractJsonPath("query{orders(first:2,sortBy:{direction:DESC,field:CREATION_DATE}){totalCount}}","data.orders");
        assertThat(sort.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.CREATED_AT,false));
        dgs.executeAndExtractJsonPath("query{orders(first:2){totalCount}}","data.orders");
        assertThat(sort.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.CREATED_AT,false));
    }

    @Test void statusAndLastModifiedSortsReachTheOwningReadService() {
        auth("admin","ADMIN"); ArgumentCaptor<OrderSort> sort=ArgumentCaptor.forClass(OrderSort.class);
        when(orders.scrollOrders(any(Authentication.class),sort.capture(),any(),any(Limit.class)))
                .thenReturn(new OrderPage(Window.from(List.<OrderView>of(),i->ScrollPosition.keyset(),false),0));
        dgs.executeAndExtractJsonPath("query{orders(first:2,sortBy:{direction:ASC,field:STATUS}){totalCount}}","data.orders");
        assertThat(sort.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.STATUS,true));
        dgs.executeAndExtractJsonPath("query{orders(first:2,sortBy:{direction:ASC,field:LAST_MODIFIED_AT}){totalCount}}","data.orders");
        assertThat(sort.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.LAST_MODIFIED_AT,true));
    }

    @Test void unsupportedOrderArgumentsAndSortsFailClosed() {
        auth("admin","ADMIN");
        String[][] cases={
            {"query{orders(first:2,filter:{search:\"x\"}){totalCount}}","filter is not implemented"},
            {"query{orders(first:2,where:{ids:[\"x\"]}){totalCount}}","where filter is not implemented"},
            {"query{orders(first:2,search:\"x\"){totalCount}}","search is not implemented"},
            {"query{orders(first:2,channel:\"c\"){totalCount}}","channel filtering is not implemented"},
            {"query{orders(last:2){totalCount}}","forward-only pagination"},
            {"query{order(externalReference:\"erp-1\"){id}}","externalReference lookup is not implemented"}
        };
        for(String[] item:cases) assertThatThrownBy(()->dgs.executeAndExtractJsonPath(item[0],"$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class).hasMessageContaining(item[1]);
        for(String field:new String[]{"NUMBER","RANK","CUSTOMER","PAYMENT","FULFILLMENT_STATUS"})
            assertThatThrownBy(()->dgs.executeAndExtractJsonPath(
                    "query{orders(first:2,sortBy:{direction:ASC,field:"+field+"}){totalCount}}","$"))
                    .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                    .hasMessageContaining("sort field "+field+" is not implemented");
    }

    @Test void rawUuidAndKeylessOrderIdsAreRejected() {
        auth("admin","ADMIN");
        assertThatThrownBy(()->dgs.executeAndExtractJsonPath("query($id:ID!){order(id:$id){id}}","$",
                Map.of("id",UUID.randomUUID().toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class).hasMessageContaining("Invalid global ID");
        assertThatThrownBy(()->dgs.executeAndExtractJsonPath("query{order{id}}","$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class).hasMessageContaining("order requires its id");
    }

    @Test void cursorFromAnotherSortIsRejectedWithTheValidationCode() {
        auth("admin","ADMIN");
        String cursor=cursors.toCursor(ScrollPosition.forward(Map.of("status","PLACED","id",UUID.randomUUID())));
        assertThatThrownBy(()->dgs.executeAndExtractJsonPath(
                "query($after:String){orders(first:1,after:$after){totalCount}}","$",Map.of("after",cursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex->assertThat(((com.netflix.graphql.dgs.exceptions.QueryException)ex)
                        .getErrors().get(0).getExtensions()).containsEntry("errorCode","VAL-001"));
    }

    @Test void unauthenticatedOrderManagementReadIsNotSuccessful() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(()->dgs.executeAndExtractJsonPath("query{orders(first:1){totalCount}}","$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

    @Test void requestedPageSizeReachesTheOwningUseCase() {
        auth("admin","ADMIN"); ArgumentCaptor<Limit> limit=ArgumentCaptor.forClass(Limit.class);
        when(orders.scrollOrders(any(Authentication.class),any(OrderSort.class),any(),limit.capture()))
                .thenReturn(new OrderPage(Window.from(List.<OrderView>of(),i->ScrollPosition.keyset(),false),0));
        dgs.executeAndExtractJsonPath("query{orders(first:7){totalCount}}","data.orders");
        assertThat(limit.getValue().max()).isEqualTo(7);
    }

    @Test void checkoutReadsOwnedCartAndUsesTheExistingCatalogSpi() {
        auth("buyer","USER"); UUID cart=UUID.randomUUID(),line=UUID.randomUUID(),variantId=UUID.randomUUID(),product=UUID.randomUUID();
        when(checkouts.findCheckout(eq(cart),any(Authentication.class))).thenReturn(checkout(cart,line,variantId));
        when(variants.findVariantById(eq(variantId),any(Authentication.class))).thenReturn(Optional.of(variant(variantId,product)));
        Map<String,Object> result=dgs.executeAndExtractJsonPath(
                "query($id:ID!){checkout(id:$id){id quantity lines{id quantity variant{id sku}}}}",
                "data.checkout",Map.of("id",gid("Checkout",cart)));
        assertThat(result).containsEntry("id",gid("Checkout",cart)).containsEntry("quantity",2);
        @SuppressWarnings("unchecked") List<Map<String,Object>> lines=(List<Map<String,Object>>)result.get("lines");
        assertThat(lines).hasSize(1).first().containsEntry("id",gid("CheckoutLine",line)).containsEntry("quantity",2);
        @SuppressWarnings("unchecked") Map<String,Object> v=(Map<String,Object>)lines.get(0).get("variant");
        assertThat(v).containsEntry("id",gid("ProductVariant",variantId)).containsEntry("sku","SKU-1");
    }

    @Test void checkoutLinesAddBindsInputsAndReturnsTheOwnerProjection() {
        auth("buyer","USER"); UUID cart=UUID.randomUUID(),line=UUID.randomUUID(),variantId=UUID.randomUUID();
        when(checkouts.addLines(eq(cart),any(),any(Authentication.class))).thenReturn(checkout(cart,line,variantId));
        when(variants.findVariantById(eq(variantId),any(Authentication.class))).thenReturn(Optional.of(variant(variantId,UUID.randomUUID())));
        Map<String,Object> result=dgs.executeAndExtractJsonPath(
                "mutation($id:ID!,$lines:[CheckoutLineInput!]!){checkoutLinesAdd(id:$id,lines:$lines){checkout{id quantity lines{id quantity}} checkoutErrors{code} errors{code}}}",
                "data.checkoutLinesAdd",Map.of("id",gid("Checkout",cart),"lines",List.of(Map.of("variantId",gid("ProductVariant",variantId),"quantity",2))));
        @SuppressWarnings("unchecked") Map<String,Object> c=(Map<String,Object>)result.get("checkout");
        assertThat(c.get("id")).isEqualTo(gid("Checkout",cart));
        assertThat(result.get("checkoutErrors")).isEqualTo(List.of()); assertThat(result.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<CheckoutSpi.LineAdd>> captured=ArgumentCaptor.forClass(List.class);
        verify(checkouts).addLines(eq(cart),captured.capture(),any(Authentication.class));
        assertThat(captured.getValue()).containsExactly(new CheckoutSpi.LineAdd(variantId,2));
    }

    @Test void checkoutDeleteRequiresAdmin() {
        auth("admin","ADMIN"); UUID cart=UUID.randomUUID();
        Map<String,Object> result=dgs.executeAndExtractJsonPath(
                "mutation($id:ID!){checkoutDelete(id:$id){errors{code}}}","data.checkoutDelete",Map.of("id",gid("Checkout",cart)));
        assertThat(result.get("errors")).isEqualTo(List.of());
        verify(orderCommands).requireAdmin(); verify(checkouts).deleteAsAdmin(cart);
    }
}
