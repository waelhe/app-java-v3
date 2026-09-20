package com.marketplace.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L44 (neighborhood community plan §5 — direct neighbor messages) — the
 * acceptance criteria over the REAL chain: HTTP → the resource-server
 * chain → the recipient existence gate through the REAL identity seam
 * ({@code UserLookupPortImpl} → the seeded {@code users} rows) → V7's
 * real {@code conversations} schema + V67's partial unique expressive
 * index → the SAME existing access points (read, messages, unread, read)
 * the booking conversations already ride.
 *
 * <p>Every test uses its OWN random user pairs (no test-level transaction
 * — each service call commits its own, the L41/L42/L45 convention), so
 * the shared container's state never bleeds between tests.
 *
 * <p>Acceptance criteria: (1) two users open a direct conversation and
 * exchange messages through the EXISTING endpoints; (2) the second open
 * — in EITHER order — returns the SAME conversation (the canonical pair
 * + the expressive index); (3) a direct conversation never appears in
 * the booking search path ({@code findByBookingId}); (4) the booking
 * conversation flow stays byte-byte — a booking conversation and a
 * direct conversation for the SAME pair coexist as two threads by
 * design; (5) self ⇒ 400 and unknown recipient ⇒ 404 (the rate-window
 * 429 lives in {@code DirectConversationRateLimitIntegrationTest}); plus
 * the house guards: the V67 unique floor catches even a NON-canonical
 * duplicate pair, and the 401-anonymous seam.
 *
 * <p>Criterion 6 (purge/export work as-is) is the plan's own "لا تغيير"
 * — the existing {@code MessagingContentPurgeAdapter} /
 * {@code MessagingExportAdapter} tests stay green unmodified: the direct
 * conversation is a {@code conversations} row exactly like a booking one
 * (the adapters key on participants and sent messages, never on type).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DirectConversationModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    /** The caller-id seam (the house convention — the token carries authn, this carries the id). */
    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    /** Delivery observation only — the WebSocket push is not the logic under test. */
    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationRepository conversationRepository;

    /** Seeds a real users row — the REAL UserLookupPortImpl resolves the recipient through it. */
    private UUID seededUser(String tag) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, userId, "l44-" + tag + "-" + userId + "@example.com",
                "l44-" + tag, "CONSUMER");
        return userId;
    }

    private void asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
    }

    private String openDirectOverHttp(UUID requesterId, UUID recipientId, int expectedStatus) throws Exception {
        asCaller(requesterId);
        return mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\": \"" + recipientId + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private UUID conversationId(String body) throws Exception {
        return UUID.fromString(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(body).get("id").asText());
    }

    // ---------- criterion 1: open + exchange over the EXISTING endpoints ----------

    @Test
    void criterion1_twoNeighborsOpenAndExchangeMessagesOverExistingEndpoints() throws Exception {
        UUID ahmad = seededUser("ahmad");
        UUID layla = seededUser("layla");

        String body = openDirectOverHttp(ahmad, layla, 201);
        UUID conversationId = conversationId(body);

        // The existing send point carries the direct conversation — same
        // row shape, same participation gate.
        asCaller(ahmad);
        mockMvc.perform(post("/api/v1/messages/conversations/{id}/messages", conversationId)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Salam Layla — is the courtyard still available?\"}"))
                .andExpect(status().isCreated());

        // The counterparty reads the thread through the existing list
        // point (participation, never type).
        asCaller(layla);
        mockMvc.perform(get("/api/v1/messages/conversations/{id}/messages", conversationId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].senderId").value(ahmad.toString()));

        // The unread badge counts her unread, and read clears it — both
        // existing points, byte-byte.
        mockMvc.perform(get("/api/v1/messages/conversations/{id}/unread", conversationId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
        mockMvc.perform(post("/api/v1/messages/conversations/{id}/read", conversationId)
                        .with(jwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/messages/conversations/{id}/unread", conversationId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    // ---------- criterion 2: idempotent per pair, EITHER order ----------

    @Test
    void criterion2_secondOpenEitherOrderReturnsTheSameConversation() throws Exception {
        UUID first = seededUser("pair-a");
        UUID second = seededUser("pair-b");

        String opening = openDirectOverHttp(first, second, 201);
        UUID conversationId = conversationId(opening);

        // The same side asks again — 200 with the SAME conversation.
        String reopenSameSide = openDirectOverHttp(first, second, 200);
        assertThat(conversationId(reopenSameSide)).isEqualTo(conversationId);

        // The OTHER side asks (reversed direction) — still the SAME
        // conversation: the pair is stored canonically, so the direction
        // of the ask is invisible to the finder.
        String reopenReversed = openDirectOverHttp(second, first, 200);
        assertThat(conversationId(reopenReversed)).isEqualTo(conversationId);

        // Exactly one live row for the pair in the table.
        Integer live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE booking_id IS NULL AND is_deleted = false "
                        + "AND ? IN (participant_a, participant_b) AND ? IN (participant_a, participant_b)",
                Integer.class, first, second);
        assertThat(live).isEqualTo(1);
    }

    // ---------- criterion 3: invisible to the booking search path ----------

    @Test
    void criterion3_directConversationNeverAnswersFindByBookingId() throws Exception {
        UUID first = seededUser("bk-a");
        UUID second = seededUser("bk-b");

        String body = openDirectOverHttp(first, second, 201);
        UUID conversationId = conversationId(body);

        // The direct conversation is stored booking-less — the marker the
        // whole layer keys on.
        assertThat(conversationRepository.findById(conversationId)
                .orElseThrow().getBookingId()).isNull();

        // The booking search path (the existing bookingId-keyed reuse)
        // cannot see it: no booking conversation exists for a fresh
        // booking id, and the direct row answers no booking query at all.
        UUID anyBookingId = UUID.randomUUID();
        assertThat(conversationRepository.findByBookingId(anyBookingId)).isEmpty();
        assertThat(conversationRepository.findByBookingId(conversationId)).isEmpty();
    }

    // ---------- criterion 4: booking conversations stay byte-byte ----------

    @Test
    void criterion4_bookingAndDirectConversationsCoexistAsTwoThreads() throws Exception {
        // The REAL chain for the booking leg too (the CI round-1 root cause: V7's
        // conversations_booking_id_fkey is REAL — a random bookingId with no
        // bookings row violates the FK; the module slice's entity-built schema
        // has no FK, which is why only the full context caught it — the
        // «test schema ≠ production schema» lesson once more). So this leg
        // seeds the real rows (users -> provider_listings -> bookings) and lets
        // the REAL BookingParticipantProviderAdapter read the booking — no
        // booking mocks anywhere in this test.
        UUID provider = seededUser("bk-provider");
        UUID consumer = seededUser("bk-consumer");
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'L44 booking thread seed', 'seed listing', 'APARTMENT', 10000, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, provider);
        jdbc.update("""
                INSERT INTO bookings (id, listing_id, consumer_id, provider_id, status, price_cents, currency)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 10000, 'SAR')
                ON CONFLICT (id) DO NOTHING
                """, bookingId, listingId, consumer, provider);

        // The existing booking flow, byte-byte: same booking ⇒ same
        // conversation, always 201 per the historic contract — through the
        // REAL booking participant provider reading the seeded row.
        asCaller(consumer);
        String bookingOpen1 = mockMvc.perform(post("/api/v1/messages/conversations")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\": \"" + bookingId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        asCaller(provider);
        mockMvc.perform(post("/api/v1/messages/conversations")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\": \"" + bookingId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(conversationId(bookingOpen1).toString()));

        // The direct channel for the SAME pair is a SECOND, independent
        // thread — the uniqueness is scoped to booking_id IS NULL by
        // design (exactly as two bookings already are two threads).
        String directOpen = openDirectOverHttp(consumer, provider, 201);
        assertThat(conversationId(directOpen)).isNotEqualTo(conversationId(bookingOpen1));

        Integer threads = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE is_deleted = false "
                        + "AND ? IN (participant_a, participant_b) AND ? IN (participant_a, participant_b)",
                Integer.class, provider, consumer);
        assertThat(threads).isEqualTo(2);
    }

    // ---------- criterion 5's service-level gates ----------

    @Test
    void criterion5_selfConversationIs400() throws Exception {
        UUID self = seededUser("self");
        openDirectOverHttp(self, self, 400);
    }

    @Test
    void criterion5_unknownRecipientIs404() throws Exception {
        UUID requester = seededUser("knower");
        // A well-formed UUID with no users row behind it — the honest miss.
        openDirectOverHttp(requester, UUID.randomUUID(), 404);
    }

    // ---------- house guards ----------

    /**
     * The V67 unique floor, proven at the DB level the way the reports
     * layer proves its own (the L30 G-N1 precedent): a raw NON-canonical
     * duplicate pair — the exact row the application can never produce
     * (the service always stores canonically) — is still rejected by the
     * LEAST/GREATEST expressive index. Order cannot cheat the guard.
     */
    @Test
    void v67UniqueFloorRejectsEvenNonCanonicalDuplicatePairs() {
        UUID a = seededUser("floor-a");
        UUID b = seededUser("floor-b");

        jdbc.update("""
                INSERT INTO conversations (id, booking_id, participant_a, participant_b)
                VALUES (?, NULL, ?, ?)
                """, UUID.randomUUID(), a, b);

        // The SAME pair, REVERSED column order — the expressive index
        // normalizes it away, so the insert must fail.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO conversations (id, booking_id, participant_a, participant_b)
                VALUES (?, NULL, ?, ?)
                """, UUID.randomUUID(), b, a))
                .hasMessageContaining("uq_conversations_direct_pair");
    }

    @Test
    void anonymousCallerIs401() throws Exception {
        mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
