package com.marketplace.provider;

import test.config.IntegrationContainers;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the business
 * claim cycle end to end over the REAL chain — HTTP → the resource-server
 * chain → the {@code @PreAuthorize} gates → {@link ProviderClaimService}
 * → V233's REAL schema (the CHECK, the one-live-claim partial unique
 * index, the Envers mirrors) — the register's confirmed gap
 * («لا دورة مطالبة/تحقق/اعتراض مقيسة») closed by measurement.
 *
 * <p><b>The measured journey (the register's J14 acceptance):</b> the
 * platform pre-seeds an unclaimed page (ADMIN) → a member claims it
 * (201, PENDING) → a second member's claim answers the one-live-claim
 * 409 → the administrator REJECTS (the page stays unowned; the claimant
 * re-claims — 201) → the administrator APPROVES (the handover measured
 * on the row itself: {@code provider_profiles.user_id = claimant} AND
 * {@code claimed_at} set) → the new owner drives the EXISTING owner-gated
 * {@code PUT /providers/{id}} (200 — the claim completed the ownership
 * handover the surface has always gated on) → a claim on the now-OWNED
 * page answers 409 → the withdraw branch frees a page for the next
 * claimant (204, then a fresh claim 201). The Envers trail
 * ({@code provider_claims_aud}) is counted as the cycle's own record.
 *
 * <p>Schema honesty: the {@code LoanDisputeJourneyIntegrationTest}
 * pattern verbatim — Flyway enabled, {@code ddl-auto=none}, a dedicated
 * container, so V233 is the schema the cycle runs on. The caller
 * identity is the standard {@code @MockitoBean} boundary
 * (the {@code NeighborhoodMembershipModuleIntegrationTest} shape: the
 * REAL resource-server chain accepts JWT authentications only, so the
 * member rides the {@code jwt()} post-processor and the administrator
 * the {@code adminJwt()} one) while every gate, state machine, unique
 * index, and ownership handover in between is production code. Each
 * test mints its OWN random user/page ids (no shared state).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderClaimJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the house journey precedents (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The administrative post-processor (the membership test's own
     * precedent): the REAL resource-server chain on the full integration
     * context accepts JWT authentications only — the admin JWT carries
     * {@code ROLE_ADMIN} for both the {@code /api/v1/admin/**} chain rule
     * and the service's own {@code @PreAuthorize} gates.
     */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject("j14-claim-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * The member's post-processor: an authenticated platform member. The
     * claim surfaces gate on authentication only; the owner-update leg
     * rides {@code ROLE_PROVIDER} (the platform role the identity module
     * grants a business owner — the claim hands the PAGE over, the role
     * is the member's own platform standing, minted here exactly as the
     * JWT would carry it).
     */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor providerJwt() {
        return jwt().jwt(j -> j.subject("j14-claimant"))
                .authorities(new SimpleGrantedAuthority("ROLE_CONSUMER"),
                        new SimpleGrantedAuthority("ROLE_PROVIDER"));
    }

    /** The caller-identity seam: every controller leg reads the stub. */
    private void asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
    }

    /** Seeds an unclaimed page over the REAL admin channel; returns its id. */
    private UUID seedUnclaimedPage(String displayName) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/providers")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"" + displayName + "\", \"bio\": \"الصفحة القابلة للمطالبة\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("id").asText());
    }

    /** Claims the page over the REAL member channel; returns the claim id. */
    private UUID claimOverHttp(UUID pageId, UUID claimantId, String contactChannel,
                               int expectedStatus) throws Exception {
        asCaller(claimantId);
        String body = mockMvc.perform(post("/api/v1/providers/{id}/claim", pageId)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactChannel\": \"" + contactChannel + "\","
                                + " \"note\": \"أنا مالك هذا العمل — الرخصة التجارية 1149\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        if (expectedStatus != 201) {
            return null;
        }
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("id").asText());
    }

    /** The admin's verdict over the REAL admin channel. */
    private void reviewOverHttp(UUID claimId, String decision) throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider-claims/{claimId}/review", claimId)
                        .queryParam("decision", decision)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"قرار الإدارة بعد مطابقة المستندات\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED".equals(decision) ? "APPROVED" : "REJECTED"));
    }

    /**
     * THE J14 acceptance journey: pre-seed → claim → one-live-claim 409 →
     * REJECT (re-claim works) → APPROVE (the handover measured on the row:
     * user_id AND claimed_at) → the new owner drives the EXISTING
     * owner-gated update surface → the now-owned page answers claims 409.
     */
    @Test
    void theClaimCycle_rejectThenApprove_measuresTheOwnershipHandover() throws Exception {
        UUID claimant = UUID.randomUUID();
        UUID page = seedUnclaimedPage("مخبز القصيّة");

        // The member claims the pre-seeded page — born PENDING.
        UUID claim = claimOverHttp(page, claimant, "+963-9xx-xxx-xxx", 201);
        assertThat(claim).isNotNull();
        assertThat(jdbc.queryForObject("SELECT state FROM provider_claims WHERE id = ?", String.class, claim))
                .isEqualTo("PENDING");

        // A second member's claim answers the one-live-claim 409.
        claimOverHttp(page, UUID.randomUUID(), "+963-9yy-yyy-yyy", 409);

        // The admin REJECTS: the page stays unowned, and the claimant's
        // recovery path is a FRESH claim (the live-claim gate no longer
        // blocks once the state moved).
        reviewOverHttp(claim, "REJECT");
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM provider_profiles WHERE id = ?", UUID.class, page)).isNull();
        UUID freshClaim = claimOverHttp(page, claimant, "+963-9xx-xxx-xxx", 201);
        assertThat(freshClaim).isNotNull();

        // The admin APPROVES the fresh claim: the handover measured on the
        // row itself — user_id = claimant AND claimed_at set (J14's own
        // acceptance proof), one transaction with the claim's APPROVED flip.
        reviewOverHttp(freshClaim, "APPROVE");
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM provider_profiles WHERE id = ?", UUID.class, page))
                .isEqualTo(claimant);
        assertThat(jdbc.queryForObject(
                "SELECT claimed_at FROM provider_profiles WHERE id = ?", java.sql.Timestamp.class, page))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM provider_claims WHERE id = ?", String.class, freshClaim))
                .isEqualTo("APPROVED");

        // The new owner drives the EXISTING owner-gated update surface —
        // the claim completed the ownership handover the surface has
        // always gated on (the register's measured bridge: the claim
        // cycle's APPROVE is what makes this 200).
        asCaller(claimant);
        mockMvc.perform(put("/api/v1/providers/{id}", page)
                        .with(providerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"مخبز القصيّة — الفرع الرئيسي\","
                                + " \"bio\": \"الخبز الطازج يومياً منذ 2019\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("مخبز القصيّة — الفرع الرئيسي"));

        // A claim on the now-OWNED page answers 409 — only the platform's
        // unclaimed pages are claimable.
        claimOverHttp(page, UUID.randomUUID(), "+963-9zz-zzz-zzz", 409);

        // The Envers trail is the cycle's own record: the fresh claim
        // carries its submit + decision revisions.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM provider_claims_aud WHERE id = ?", Integer.class, freshClaim))
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * The withdraw branch: the claimant takes their own PENDING claim back
     * (204), the page frees for the next claimant (201), and the board
     * reads the live queue through the REAL admin read.
     */
    @Test
    void theWithdrawBranch_freesThePageAndTheBoardReadsTheQueue() throws Exception {
        UUID firstClaimant = UUID.randomUUID();
        UUID nextClaimant = UUID.randomUUID();
        UUID page = seedUnclaimedPage("ورشة النور للسيارات");

        // The first member claims; the board sees the live PENDING claim.
        UUID claim = claimOverHttp(page, firstClaimant, "+963-9xx-xxx-xxx", 201);
        mockMvc.perform(get("/api/v1/admin/provider-claims")
                        .queryParam("providerId", page.toString())
                        .queryParam("state", "PENDING")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(claim.toString()));

        // The claimant withdraws their own claim — 204, no live claim left.
        asCaller(firstClaimant);
        mockMvc.perform(delete("/api/v1/providers/me/claims/{claimId}", claim)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT state FROM provider_claims WHERE id = ?",
                String.class, claim)).isEqualTo("WITHDRAWN");

        // The page is free: the NEXT claimant's claim lands (201) — the
        // withdrawal freed the one-live-claim slot.
        UUID nextClaim = claimOverHttp(page, nextClaimant, "+963-9yy-yyy-yyy", 201);
        assertThat(nextClaim).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM provider_claims WHERE provider_id = ? AND state = 'PENDING'",
                Integer.class, page)).isEqualTo(1);
    }

    /**
     * The house guards over the REAL chain: an unknown page answers 404
     * BEFORE any write, and the anonymous caller never passes the
     * resource-server chain (401 — the journey's every leg is
     * authenticated by construction).
     */
    @Test
    void unknownPageAnswers404AndAnonymousAnswers401() throws Exception {
        asCaller(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/providers/{id}/claim", UUID.randomUUID())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactChannel\": \"+963-9xx-xxx-xxx\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/providers/{id}/claim", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactChannel\": \"+963-9xx-xxx-xxx\"}"))
                .andExpect(status().isUnauthorized());
    }
}
