package com.marketplace.provider;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;
import com.marketplace.shared.security.CurrentUserProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W5b (the journeys register's J29 row — تفويض فريق مزود الخدمة بصلاحيات
 * ونطاق): the delegation journey over the REAL chain — HTTP → the
 * resource-server chain → the ownership gates → V234's real schema (the
 * CHECKs, the partial unique index, the Envers mirror) → the REAL
 * owner-gated surfaces the delegation extends (the profile update, the
 * declared business hours).
 *
 * <p>The house convention for the caller identity (the community module's
 * own integration tests, the ProviderBusinessPageIntegration shape): a
 * request-scoped {@code jwt()} processor for the security chain with the
 * {@link CurrentUserProvider} mocked per-step — the subject resolution is
 * another module's concern, not this wave's.
 *
 * <p>The journey (the register's own wording, «تفويض فريق/فرع بصلاحيات
 * ونطاق»): the owner registers a provider → delegates an EDITOR seat
 * (201) → the duplicate grant answers 409 (the one-live-seat law) → the
 * EDITOR maintains the business's own profile and declared hours through
 * the REAL owner-gated surfaces (200s, the changes read back) → a
 * stranger's write answers the house 403 → the owner revokes the seat
 * (204) → the REVOKED member's write now fails (403 — «موظف تجاوز نطاقه»
 * proven) → the owner still can (200).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ProviderTeamJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    // ---- the house identity fixtures -----------------------------------------

    /** The house convention: stub the mocked provider with the caller's id. */
    private void asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
    }

    /** The registration surface's own role requirement ({@code hasRole('CONSUMER')}). */
    private static RequestPostProcessor asConsumer(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CONSUMER"));
    }

    /** Every provider-side surface's role requirement ({@code hasRole('PROVIDER')}). */
    private static RequestPostProcessor asProviderUser(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_PROVIDER"));
    }

    /** The house JSON read (the ProviderFollowIntegration shape — Jackson 3). */
    private static String fieldOf(String body, String field) {
        return JsonMapper.builder().build().readTree(body).get(field).asString();
    }

    // ---- the journey ------------------------------------------------------------

    @Test
    void theFullDelegationJourneyFromGrantToRevokedAuthority() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID editorUserId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();

        // (1) The owner registers the provider through the real chain —
        // the identity the whole journey delegates FROM.
        asCaller(ownerId);
        UUID providerUuid = UUID.fromString(fieldOf(mockMvc.perform(post("/api/v1/providers")
                        .with(asConsumer(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"مكتب القضية للعقارات\", \"bio\": \"وسيط مستقل\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andReturn().getResponse().getContentAsString(), "id"));

        // (2) The owner delegates an EDITOR seat — 201, the wire view carries
        // the delegation's own facts.
        String memberId = fieldOf(mockMvc.perform(post("/api/v1/providers/{id}/team", providerUuid)
                        .with(asProviderUser(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\": \"" + editorUserId
                                        + "\", \"role\": \"EDITOR\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.memberUserId").value(editorUserId.toString()))
                        .andExpect(jsonPath("$.role").value("EDITOR"))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andReturn().getResponse().getContentAsString(), "id");

        // (3) The duplicate grant answers 409 — the one-live-seat law, taught
        // BEFORE any write; the owner-as-member asks for the same law.
        mockMvc.perform(post("/api/v1/providers/{id}/team", providerUuid)
                        .with(asProviderUser(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\": \"" + editorUserId
                                + "\", \"role\": \"MANAGER\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/providers/{id}/team", providerUuid)
                        .with(asProviderUser(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\": \"" + ownerId
                                + "\", \"role\": \"MANAGER\"}"))
                .andExpect(status().isConflict());

        // (4) The EDITOR maintains the business's own profile through the
        // REAL owner-gated surface (the delegation's whole point) — and the
        // change reads back.
        asCaller(editorUserId);
        mockMvc.perform(put("/api/v1/providers/{id}", providerUuid)
                        .with(asProviderUser(editorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"مكتب القضية — الفرع الرئيسي\", \"bio\": \"بعد التفويض\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("مكتب القضية — الفرع الرئيسي"));
        mockMvc.perform(get("/api/v1/providers/{id}", providerUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("مكتب القضية — الفرع الرئيسي"));

        // (5) The EDITOR declares the business hours through the same
        // delegated surface — the W2 block, now team-maintained.
        mockMvc.perform(put("/api/v1/providers/{id}/business-hours", providerUuid)
                        .with(asProviderUser(editorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\": [{\"dayOfWeek\": \"SUNDAY\", "
                                + "\"opensAt\": \"09:00\", \"closesAt\": \"17:00\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayOfWeek").value("SUNDAY"));

        // (6) A stranger — no ownership, no delegation — answers the house
        // 403 on the same surface (the exact pre-W5b semantics).
        asCaller(strangerId);
        mockMvc.perform(put("/api/v1/providers/{id}", providerUuid)
                        .with(asProviderUser(strangerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"استيلاء\", \"bio\": \"لا\"}"))
                .andExpect(status().isForbidden());

        // (7) The owner revokes the seat — 204, the row and its Envers trail
        // stay, the authority ends at commit.
        asCaller(ownerId);
        mockMvc.perform(delete("/api/v1/providers/{id}/team/{memberId}", providerUuid, memberId)
                        .with(asProviderUser(ownerId)))
                .andExpect(status().isNoContent());
        Integer liveSeats = jdbc.queryForObject(
                "SELECT count(*) FROM provider_team_members WHERE id = ? AND status = 'ACTIVE'",
                Integer.class, UUID.fromString(memberId));
        org.assertj.core.api.Assertions.assertThat(liveSeats).isZero();

        // (8) The REVOKED member's write now fails — «موظف تجاوز نطاقه»,
        // the failure branch the journey exists to prove.
        asCaller(editorUserId);
        mockMvc.perform(put("/api/v1/providers/{id}", providerUuid)
                        .with(asProviderUser(editorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"بعد السحب\", \"bio\": \"لا صلاحية\"}"))
                .andExpect(status().isForbidden());

        // (9) The owner still can — the authority the delegation never moved.
        asCaller(ownerId);
        mockMvc.perform(put("/api/v1/providers/{id}", providerUuid)
                        .with(asProviderUser(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"مكتب القضية — بإدارة المالك\", \"bio\": \"الصلاحية الأصلية\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("مكتب القضية — بإدارة المالك"));
    }
}
