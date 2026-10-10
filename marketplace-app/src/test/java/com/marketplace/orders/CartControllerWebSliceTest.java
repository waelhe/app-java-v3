package com.marketplace.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The web-layer slice for the cart line's HTTP contract — the exact leg the
 * journey ITs exercise: Jackson 3 deserialization + {@code @Valid} bean
 * validation + the taxonomy ProblemDetail mapping, with NO truncation of the
 * response in the failure message (the journeys' body() helpers cut a
 * ProblemDetail at 300 chars — before the fieldErrors that name the violated
 * constraint; this slice prints the whole body, so a VAL-001 root is visible
 * on the first failure).
 */
@WebMvcTest(controllers = CartController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
class CartControllerWebSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrdersService ordersService;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser
    void theJourneysExactValidBodyIsAccepted() throws Exception {
        UUID product = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(caller);
        when(ordersService.addCartItem(eq(caller), eq(product), anyInt(), anyLong(), anyString()))
                .thenReturn(CartItem.of(UUID.randomUUID(), product, 5, 1500L, "SAR"));

        String body = """
                {"productId":"%s","quantity":3,"unitAmountMinor":1500,"currency":"SAR"}"""
                .formatted(product);

        MvcResult result = mockMvc.perform(post("/api/v1/me/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the journey's exact body through the real web leg — full response: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
    }
}
