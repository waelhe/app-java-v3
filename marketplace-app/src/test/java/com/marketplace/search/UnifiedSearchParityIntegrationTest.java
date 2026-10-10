package com.marketplace.search;

import com.marketplace.ai.MarketplaceSearchTools;
import com.marketplace.community.EventCategory;
import com.marketplace.community.EventRegistration;
import com.marketplace.community.NeighborhoodEvent;
import com.marketplace.community.NeighborhoodEventRepository;
import com.marketplace.community.NeighborhoodPost;
import com.marketplace.community.NeighborhoodPostRepository;
import com.marketplace.community.PostCategory;
import com.marketplace.institutions.Institution;
import com.marketplace.institutions.InstitutionRepository;
import com.marketplace.institutions.InstitutionType;
import com.marketplace.knowledge.KnowledgeCategory;
import com.marketplace.knowledge.KnowledgeEntry;
import com.marketplace.knowledge.KnowledgeEntryRepository;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchResponse;
import com.marketplace.shared.api.UnifiedSearchSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.IntegrationContainers;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the measured parity and the «لا تسرب» gate on the
 * real PostgreSQL.
 *
 * <p><b>Parity:</b> REST's orchestrator ({@code UnifiedSearchService.search})
 * and the AI tool's path ({@code MarketplaceSearchPort.unified} — the exact
 * method the {@code search_unified} tool calls) must return equivalent hit
 * sets for the same criteria. Both ride the same adapters, so the test
 * pins that equivalence on every seeded domain instead of asserting it by
 * construction alone.
 *
 * <p><b>Visibility:</b> a moderator-hidden post never appears on either
 * path (the plan's «لا تسرب عبر المخفي» gate), the location scoping holds,
 * every consulted source answers, and the consultation is measured (the
 * hit counters move — the «قياس» requirement, D-06's evidence source).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UnifiedSearchParityIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private UnifiedSearchService unifiedSearchService;

    @Autowired
    private MarketplaceSearchPort marketplaceSearchPort;

    @Autowired
    private MarketplaceSearchTools marketplaceSearchTools;

    @Autowired
    private NeighborhoodPostRepository postRepository;

    @Autowired
    private NeighborhoodEventRepository eventRepository;

    @Autowired
    private KnowledgeEntryRepository knowledgeEntryRepository;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID REPRESENTATIVE = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();

    @Test
    @WithMockUser(roles = "CONSUMER")
    void unifiedSearch_restAndAiAnswerEquivalentSets_andVisibilityHolds() {
        // The four domains, one seeded row each — plus the cross-neighborhood
        // post (absent when location-scoped) and the moderator-hidden post
        // (absent everywhere, matching text included).
        NeighborhoodPost visible = postRepository.save(NeighborhoodPost.post(
                AUTHOR, LOCATION, PostCategory.QUESTION,
                "طلب سباكة عاجل في الحي",
                "أبحث عن سباكة موثوق داخل الحي — من يرشح؟",
                Clock.systemUTC()));
        NeighborhoodPost elsewhere = postRepository.save(NeighborhoodPost.post(
                AUTHOR, OTHER_LOCATION, PostCategory.QUESTION,
                "طلب سباكة عاجل في حي آخر",
                "منشور خارج النطاق المطلوب — سباكة",
                Clock.systemUTC()));
        NeighborhoodPost hidden = postRepository.save(NeighborhoodPost.post(
                AUTHOR, LOCATION, PostCategory.QUESTION,
                "طلب سباكة عاجل مخفي",
                "أخفاه المشرف — سباكة — يجب ألا يظهر في أي مسار",
                Clock.systemUTC()));
        jdbcTemplate.update(
                "UPDATE neighborhood_posts SET status = 'HIDDEN_BY_MODERATOR' WHERE id = ?",
                hidden.getId());

        NeighborhoodEvent event = eventRepository.save(NeighborhoodEvent.event(
                AUTHOR, LOCATION, EventCategory.MARKET,
                "فعالية سوق رمضان",
                "سوق شتوي داخل الحي",
                Instant.now(Clock.systemUTC()).plusSeconds(86_400),
                Instant.now(Clock.systemUTC()).plusSeconds(90_000),
                "الساحة الشمالية", "لجنة الفعاليات", 100, EventRegistration.OPEN));

        KnowledgeEntry entry = knowledgeEntryRepository.save(KnowledgeEntry.contribute(
                AUTHOR, LOCATION, KnowledgeCategory.HISTORY,
                "أدلة دليل المعالم",
                "دليل معالم الحي القديمة"));

        Institution institution = institutionRepository.save(Institution.register(
                "مركز السلام الخيري", InstitutionType.CHARITY,
                REPRESENTATIVE, LOCATION, "شارع السلام 12", null, null,
                "مركز السلام لخدمة الحي", Clock.systemUTC()));

        // --- The REST path: every consulted source answers its own marker.
        UnifiedSearchResponse rest = unifiedSearchService.search(
                new UnifiedSearchQuery("سباكة عاجلة", null, 5));

        // Every port is consulted (the full source set, declaration order);
        // nothing degrades.
        assertThat(rest.consultedSources()).containsExactly(
                UnifiedSearchSource.COMMUNITY_EVENT,
                UnifiedSearchSource.COMMUNITY_POST,
                UnifiedSearchSource.INSTITUTION,
                UnifiedSearchSource.KNOWLEDGE);
        assertThat(rest.degradedSources()).isEmpty();

        // The visible post answers under its source; the hidden and the
        // cross-neighborhood posts never appear.
        List<UUID> restHitIds = rest.hits().stream().map(h -> h.id()).toList();
        assertThat(restHitIds).contains(visible.getId());
        assertThat(restHitIds).doesNotContain(hidden.getId(), elsewhere.getId());
        assertThat(rest.hits()).allSatisfy(h ->
                assertThat(h.source()).isEqualTo(UnifiedSearchSource.COMMUNITY_POST));

        assertThat(unifiedSearchService.search(new UnifiedSearchQuery("سوق رمضان", null, 5))
                .hits()).anySatisfy(h -> {
                    assertThat(h.source()).isEqualTo(UnifiedSearchSource.COMMUNITY_EVENT);
                    assertThat(h.id()).isEqualTo(event.getId());
                });
        assertThat(unifiedSearchService.search(new UnifiedSearchQuery("دليل المعالم", null, 5))
                .hits()).anySatisfy(h -> {
                    assertThat(h.source()).isEqualTo(UnifiedSearchSource.KNOWLEDGE);
                    assertThat(h.id()).isEqualTo(entry.getId());
                });
        assertThat(unifiedSearchService.search(new UnifiedSearchQuery("مركز السلام", null, 5))
                .hits()).anySatisfy(h -> {
                    assertThat(h.source()).isEqualTo(UnifiedSearchSource.INSTITUTION);
                    assertThat(h.id()).isEqualTo(institution.getId());
                });

        // --- The AI path: the exact port method the search_unified tool
        // calls, on the same query — PARITY (equivalent sets, the plan's
        // «REST وAI يعيدان مجموعات متكافئة جوهريًا» gate).
        UnifiedSearchResponse ai = marketplaceSearchPort.unified(
                new UnifiedSearchQuery("سباكة عاجلة", null, 5));
        assertThat(ai.hits()).isEqualTo(rest.hits());
        assertThat(ai.consultedSources()).isEqualTo(rest.consultedSources());
        assertThat(ai.degradedSources()).isEqualTo(rest.degradedSources());

        // The AI tool bean — the full tool surface (context + wrapping),
        // still equivalent to REST's answer.
        var toolResult = marketplaceSearchTools.searchUnified(
                "سباكة عاجلة", null,
                new ToolContext(Map.of("userId", AUTHOR.toString())));
        assertThat(toolResult.hits()).isEqualTo(rest.hits());
        assertThat(toolResult.degradedSources()).isEmpty();

        // The location scoping holds: scoped to the seeded location, the
        // other location's post vanishes while the visible one stays.
        UnifiedSearchResponse scoped = unifiedSearchService.search(
                new UnifiedSearchQuery("سباكة عاجلة", LOCATION, 5));
        List<UUID> scopedIds = scoped.hits().stream().map(h -> h.id()).toList();
        assertThat(scopedIds).contains(visible.getId());
        assertThat(scopedIds).doesNotContain(elsewhere.getId(), hidden.getId());

        // MEASURED: the consultation counters moved — the «قياس» gate, and
        // the evidence source D-06's later decision reads.
        assertThat(meterRegistry.counter(
                UnifiedSearchService.HITS_COUNTER, "source", "COMMUNITY_POST").count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.counter(
                UnifiedSearchService.HITS_COUNTER, "source", "KNOWLEDGE").count())
                .isGreaterThanOrEqualTo(1.0);
    }
}
