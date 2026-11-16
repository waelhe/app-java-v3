package com.marketplace.community;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L50 MVC slice: the market board surface's request validation and
 * status shape. The membership 403s (G-N3), the gate orders and the
 * grouped badge read are pinned against the REAL chain by the module
 * integration test; this slice pins the HTTP contract itself (the
 * L41/L42/L47/L49 WebMvc precedent): the type gates' 400s (invalid
 * category, invalid condition, blank fields — before any write), the
 * honest 404s, the 201 writes, the 204 deletes and the two
 * caller-scoped facts' read shape.
 */
@WebMvcTest(controllers = NeighborhoodMarketController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodMarketControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodMarketControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodMarketItemService marketService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private UUID stubCaller() {
        // The house form (the L41 slice's own note): the untyped any()
        // with the generic hint matches a null Authentication too.
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(
                org.mockito.ArgumentMatchers.<org.springframework.security.core.Authentication>any()))
                .thenReturn(userId);
        return userId;
    }

    private NeighborhoodMarketItemView itemView(UUID authorId, UUID locationId) {
        Instant at = Instant.parse("2026-10-01T20:15:00Z");
        return new NeighborhoodMarketItemView(UUID.randomUUID(), authorId, locationId,
                "FREE", "مكتب دراسي خشبي بحالة ممتازة — إهداء لأسرة طلاب",
                "LIKE_NEW", null, null, "ACTIVE", "شارع المسجد الرئيسي - مربع 2",
                true, true, at, at);
    }

    @Test
    void getBoard_member_answersThePagedBodyWithBothCallerScopedFacts() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        NeighborhoodMarketItemView view = new NeighborhoodMarketItemView(
                UUID.randomUUID(), userId, locationId,
                "FURNITURE", "أريكة جلسة عائلية 7 مقاعد", "GOOD",
                48000, "SAR", "ACTIVE", "شارع المسجد - مربع 2",
                true, true,
                Instant.parse("2026-10-01T18:30:00Z"), Instant.parse("2026-10-01T18:30:00Z"));
        when(marketService.getBoard(eq(userId), isNull(), isNull(), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(view)));

        mockMvc.perform(get("/api/v1/neighborhood/market"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorId").value(userId.toString()))
                .andExpect(jsonPath("$.content[0].sellerVerified").value(true))
                .andExpect(jsonPath("$.content[0].mine").value(true))
                .andExpect(jsonPath("$.content[0].priceCents").value(48000))
                .andExpect(jsonPath("$.content[0].priceCurrency").value("SAR"))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    void getBoard_invalidCategory_is400AtTheBoundary() throws Exception {
        stubCaller();

        mockMvc.perform(get("/api/v1/neighborhood/market")
                        .param("category", "NOT_A_CATEGORY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getBoard_categoryParsesToTheEnum_furniture() throws Exception {
        UUID userId = stubCaller();
        Instant at = Instant.parse("2026-10-01T18:30:00Z");
        NeighborhoodMarketItemView furniture = new NeighborhoodMarketItemView(
                UUID.randomUUID(), userId, UUID.randomUUID(),
                "FURNITURE", "أريكة جلسة عائلية", "GOOD",
                48000, "SAR", "ACTIVE", "شارع المسجد - مربع 2",
                false, false, at, at);
        when(marketService.getBoard(eq(userId), eq(MarketCategory.FURNITURE), isNull(),
                eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(furniture)));

        mockMvc.perform(get("/api/v1/neighborhood/market")
                        .param("category", "FURNITURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].category").value("FURNITURE"));
    }

    @Test
    void getBoard_mineAxisPassesThroughAsTrue() throws Exception {
        UUID userId = stubCaller();
        when(marketService.getBoard(eq(userId), isNull(), isNull(), eq(true), any()))
                .thenReturn(new PageImpl<>(List.of(itemView(userId, UUID.randomUUID()))));

        mockMvc.perform(get("/api/v1/neighborhood/market")
                        .param("mine", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].mine").value(true));
    }

    @Test
    void getBoard_queryTextPassesThroughToTheRead() throws Exception {
        UUID userId = stubCaller();
        when(marketService.getBoard(eq(userId), isNull(), eq("تكييف"), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/neighborhood/market")
                        .param("q", "تكييف"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void getBoard_noMembership_answers403ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        when(marketService.getBoard(eq(userId), isNull(), isNull(), eq(false), any()))
                .thenThrow(new AccessDeniedException(
                        "Join a neighborhood before reading its market board"));

        mockMvc.perform(get("/api/v1/neighborhood/market"))
                .andExpect(status().isForbidden());
    }

    @Test
    void marketCreate_validPricedBody_answers201() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T18:30:00Z");
        NeighborhoodMarketItemView priced = new NeighborhoodMarketItemView(
                UUID.randomUUID(), userId, locationId,
                "FURNITURE", "أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل", "GOOD",
                48000, "SAR", "ACTIVE", "شارع المسجد - مربع 2",
                false, true, at, at);
        when(marketService.createItem(eq(userId), eq(locationId), eq(MarketCategory.FURNITURE),
                eq("أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل"), eq(MarketCondition.GOOD),
                eq(48000), eq("SAR"), eq("شارع المسجد - مربع 2")))
                .thenReturn(priced);

        mockMvc.perform(post("/api/v1/neighborhood/market")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "category": "FURNITURE",
                                  "title": "أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل",
                                  "condition": "GOOD",
                                  "priceCents": 48000,
                                  "priceCurrency": "SAR",
                                  "locationLabel": "شارع المسجد - مربع 2"
                                }
                                """.formatted(locationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("FURNITURE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mine").value(true));
    }

    @Test
    void marketCreate_validFreeGiftBody_answers201() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(marketService.createItem(eq(userId), eq(locationId), eq(MarketCategory.FREE),
                eq("مكتب دراسي خشبي بحالة ممتازة — إهداء لأسرة طلاب"),
                eq(MarketCondition.LIKE_NEW), isNull(), isNull(),
                eq("شارع المسجد الرئيسي - مربع 2")))
                .thenReturn(itemView(userId, locationId));

        mockMvc.perform(post("/api/v1/neighborhood/market")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "category": "FREE",
                                  "title": "مكتب دراسي خشبي بحالة ممتازة — إهداء لأسرة طلاب",
                                  "condition": "LIKE_NEW",
                                  "locationLabel": "شارع المسجد الرئيسي - مربع 2"
                                }
                                """.formatted(locationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCents").doesNotExist());
    }

    @Test
    void marketCreate_invalidCategory_is400BeforeAnyWrite() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/market")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "category": "GIFTS",
                                  "title": "Title",
                                  "condition": "GOOD",
                                  "priceCents": 100,
                                  "priceCurrency": "SAR",
                                  "locationLabel": "Spot"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void marketCreate_invalidCondition_is400BeforeAnyWrite() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/market")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "category": "FREE",
                                  "title": "Title",
                                  "condition": "NEW",
                                  "locationLabel": "Spot"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void marketCreate_blankTitle_is400TheBeanValidationGate() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/market")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "category": "FREE",
                                  "title": "",
                                  "condition": "GOOD",
                                  "locationLabel": "Spot"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void marketDelete_succeeds_answers204() throws Exception {
        UUID userId = stubCaller();
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/neighborhood/market/{itemId}", itemId))
                .andExpect(status().isNoContent());
    }

    @Test
    void marketDelete_unknownItem_answers404() throws Exception {
        UUID userId = stubCaller();
        UUID itemId = UUID.randomUUID();
        when(marketService.getBoard(eq(userId), any(), any(), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of()));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Market item", itemId))
                .when(marketService).deleteByAuthor(userId, itemId);

        mockMvc.perform(delete("/api/v1/neighborhood/market/{itemId}", itemId))
                .andExpect(status().isNotFound());
    }
}
