package com.marketplace.knowledge;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D-3 (JT-19/D-30): the news engine's contracts — the VERIFIED-publisher
 * gate (the delegated-source condition: 404 unknown outlet → 409 for any
 * non-VERIFIED state, before any write), the optional geo gate's order
 * (the port's 404 before the level-3 400, and silence when no scope is
 * carried), the honesty markers (a correction lands its REQUIRED note
 * and stays displayed; a withdrawal hides the item immediately and reads
 * as the same 404), the service-owned board sort (the complete
 * {@code (published_at, id)} key — the client never picks the order),
 * and the full register-verify-publish-correct-withdraw journey as one
 * orchestration pin.
 */
@ExtendWith(MockitoExtension.class)
class NewsServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    @Mock
    private NewsPublisherRepository publisherRepository;

    @Mock
    private NewsItemRepository itemRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private NewsService service() {
        return new NewsService(publisherRepository, itemRepository, geoLookupPort, clock);
    }

    private void neighborhoodNode(UUID locationId) {
        when(geoLookupPort.getLocation(locationId)).thenReturn(new GeoLookupPort.GeoNode(
                locationId, UUID.randomUUID(), 3, "حي القضية", "Al-Qudayya", "al-qudayya", List.of()));
    }

    private NewsPublisherRequest publisherRequest() {
        return new NewsPublisherRequest("وكالة قدسيا للأنباء", "https://qudsayya-news.example");
    }

    private NewsItemRequest itemRequest(UUID publisherId) {
        return new NewsItemRequest(publisherId, "افتتاح الطريق الدائري الجديد",
                "ملخص الخبر", "https://qudsayya-news.example/road", NOW.minusSeconds(3600), null);
    }

    private NewsItemCorrectionRequest correctionRequest(UUID locationId) {
        return new NewsItemCorrectionRequest("العنوان المصحح", "الملخص المصحح",
                "https://qudsayya-news.example/road-v2", locationId, "صُحّح رقم المساحة المذكور");
    }

    // ------------------------------------------------------------------
    // The publisher's lifecycle (the admin's honest registry).
    // ------------------------------------------------------------------

    @Test
    void createPublisherBornUnverified() {
        when(publisherRepository.save(any(NewsPublisher.class))).thenAnswer(inv -> inv.getArgument(0));

        NewsPublisherResponse response = service().createPublisher(publisherRequest());

        assertThat(response.verificationState()).isEqualTo("UNVERIFIED");
        assertThat(response.name()).isEqualTo("وكالة قدسيا للأنباء");
        assertThat(response.websiteUrl()).isEqualTo("https://qudsayya-news.example");
    }

    @Test
    void approveLandsTheTrustMarkOnAnUnverifiedOutlet() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        NewsPublisherResponse response = service().verifyPublisher(publisher.getId(), true);

        assertThat(response.verificationState()).isEqualTo("VERIFIED");
    }

    @Test
    void approveReadmitsARejectedOutlet() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.rejectVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        assertThat(service().verifyPublisher(publisher.getId(), true).verificationState())
                .isEqualTo("VERIFIED");
    }

    @Test
    void rejectRefusesTheClaimAndTheRowStays() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        NewsPublisherResponse response = service().verifyPublisher(publisher.getId(), false);

        assertThat(response.verificationState()).isEqualTo("REJECTED");
        verify(publisherRepository, never()).delete(any());
    }

    @Test
    void verdictOfAnUnknownOutletAnswers404() {
        UUID unknown = UUID.randomUUID();
        when(publisherRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().verifyPublisher(unknown, true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("News publisher not found");
    }

    @Test
    void approvingAnAlreadyVerifiedOutletIs409() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.approveVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        assertThatThrownBy(() -> service().verifyPublisher(publisher.getId(), true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already VERIFIED");
    }

    @Test
    void rejectingAnAlreadyRejectedOutletIs409() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.rejectVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        assertThatThrownBy(() -> service().verifyPublisher(publisher.getId(), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already REJECTED");
    }

    // ------------------------------------------------------------------
    // The publication gate (the delegated-source condition).
    // ------------------------------------------------------------------

    @Test
    void createNewsFromAVerifiedPublisherRidesTheCompleteAttribution() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.approveVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));
        when(itemRepository.save(any(NewsItem.class))).thenAnswer(inv -> inv.getArgument(0));

        NewsItemResponse response = service().createNews(itemRequest(publisher.getId()));

        assertThat(response.publisherName()).isEqualTo("وكالة قدسيا");
        assertThat(response.publisherVerified()).isTrue();
        assertThat(response.sourceUrl()).isEqualTo("https://qudsayya-news.example/road");
        assertThat(response.publishedAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(response.corrected()).isFalse();
        assertThat(response.locationId()).isNull();
        verifyNoInteractions(geoLookupPort);
    }

    @Test
    void createNewsFromAnUnverifiedPublisherIs409BeforeAnyWrite() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        assertThatThrownBy(() -> service().createNews(itemRequest(publisher.getId())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("UNVERIFIED")
                .hasMessageContaining("VERIFIED");
        verifyNoInteractions(itemRepository);
        verifyNoInteractions(geoLookupPort);
    }

    @Test
    void createNewsFromARejectedPublisherIsTheSame409() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.rejectVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));

        assertThatThrownBy(() -> service().createNews(itemRequest(publisher.getId())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("REJECTED");
        verifyNoInteractions(itemRepository);
    }

    @Test
    void createNewsFromAnUnknownPublisherIsTheFksOwn404() {
        UUID unknown = UUID.randomUUID();
        when(publisherRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createNews(itemRequest(unknown)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("News publisher not found");
        verifyNoInteractions(itemRepository);
        verifyNoInteractions(geoLookupPort);
    }

    @Test
    void createNewsWithAnUnknownLocationIsThePortsOwn404() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.approveVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));
        UUID unknown = UUID.randomUUID();
        when(geoLookupPort.getLocation(unknown))
                .thenThrow(new ResourceNotFoundException("Location not found: " + unknown));

        assertThatThrownBy(() -> service().createNews(new NewsItemRequest(
                publisher.getId(), "t", null, "https://s.example/a", NOW, unknown)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Location not found");
        verifyNoInteractions(itemRepository);
    }

    @Test
    void createNewsWithANonNeighborhoodLocationIs400BeforeAnyWrite() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.approveVerification();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));
        UUID cityId = UUID.randomUUID();
        when(geoLookupPort.getLocation(cityId)).thenReturn(new GeoLookupPort.GeoNode(
                cityId, null, 2, "الرياض", "Riyadh", "riyadh", List.of()));

        assertThatThrownBy(() -> service().createNews(new NewsItemRequest(
                publisher.getId(), "t", null, "https://s.example/a", NOW, cityId)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verifyNoInteractions(itemRepository);
    }

    // ------------------------------------------------------------------
    // The correction (the knowledge revise shape plus the honesty markers).
    // ------------------------------------------------------------------

    @Test
    void correctNewsLandsTheHonestMarkerAndKeepsTheHistory() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "العنوان الأصلي", "الملخص الأصلي",
                "https://s.example/a", NOW.minusSeconds(7200), null);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(publisherRepository.findById(item.getPublisherId()))
                .thenReturn(Optional.of(NewsPublisher.register("وكالة قدسيا", null)));

        NewsItemResponse response = service().correctNews(item.getId(), correctionRequest(null));

        assertThat(response.title()).isEqualTo("العنوان المصحح");
        assertThat(response.summary()).isEqualTo("الملخص المصحح");
        assertThat(response.sourceUrl()).isEqualTo("https://qudsayya-news.example/road-v2");
        assertThat(response.corrected()).isTrue();
        assertThat(response.correctedAt()).isEqualTo(NOW);
        assertThat(response.correctionNote()).isEqualTo("صُحّح رقم المساحة المذكور");
        // The history is honest: the original date and the scope never re-date/re-target.
        assertThat(response.publishedAt()).isEqualTo(NOW.minusSeconds(7200));
        assertThat(response.locationId()).isNull();
    }

    @Test
    void correctNewsWithoutItsNoteIs400BeforeAnyWrite() {
        assertThatThrownBy(() -> service().correctNews(UUID.randomUUID(),
                new NewsItemCorrectionRequest("t", null, "https://s.example/a", null, "  ")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("correctionNote is required");
        assertThatThrownBy(() -> service().correctNews(UUID.randomUUID(),
                new NewsItemCorrectionRequest("t", null, "https://s.example/a", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("correctionNote is required");
        verifyNoInteractions(itemRepository);
    }

    @Test
    void correctNewsOfAWithdrawnItemIs409() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "t", null, "https://s.example/a", NOW, null);
        item.withdraw(clock);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service().correctNews(item.getId(), correctionRequest(null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("withdrawn");
    }

    @Test
    void correctNewsCannotReTargetTheScope() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "t", null, "https://s.example/a", NOW, NEIGHBORHOOD_ID);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service().correctNews(item.getId(), correctionRequest(UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("locationId cannot change on correction");
    }

    @Test
    void correctNewsOfAnUnknownItemIsTheHouse404() {
        UUID unknown = UUID.randomUUID();
        when(itemRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().correctNews(unknown, correctionRequest(null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("News item not found");
    }

    // ------------------------------------------------------------------
    // The withdrawal (the display's own off switch — immediate).
    // ------------------------------------------------------------------

    @Test
    void withdrawNewsHidesTheItemImmediately() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "t", null, "https://s.example/a", NOW, null);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        service().withdrawNews(item.getId());

        assertThat(item.isWithdrawn()).isTrue();
        assertThat(item.getWithdrawnAt()).isEqualTo(NOW);
    }

    @Test
    void withdrawNewsTwiceIs409AndNeverReDatesTheFlag() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "t", null, "https://s.example/a", NOW, null);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        service().withdrawNews(item.getId());

        assertThatThrownBy(() -> service().withdrawNews(item.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already withdrawn");
        assertThat(item.getWithdrawnAt()).isEqualTo(NOW);
    }

    @Test
    void withdrawNewsOfAnUnknownItemIsTheHouse404() {
        UUID unknown = UUID.randomUUID();
        when(itemRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().withdrawNews(unknown))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("News item not found");
    }

    // ------------------------------------------------------------------
    // The public reads (the honest display).
    // ------------------------------------------------------------------

    @Test
    void boardPassesTheServiceOwnedSortAndResolvesThePublishersInOneBatch() {
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.approveVerification();
        NewsItem item = NewsItem.publish(publisher.getId(), "t", null, "https://s.example/a", NOW, null);
        PageRequest expected = PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "publishedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        when(itemRepository.findByWithdrawnFalse(expected))
                .thenReturn(new PageImpl<>(List.of(item), expected, 1));
        when(publisherRepository.findAllById(Set.of(item.getPublisherId())))
                .thenReturn(List.of(publisher));

        var page = service().board(null, PageRequest.of(0, 20));

        assertThat(page).hasSize(1);
        assertThat(page.getContent().get(0).publisherName()).isEqualTo("وكالة قدسيا");
        assertThat(page.getContent().get(0).publisherVerified()).isTrue();
    }

    @Test
    void boardComposesTheLocationAxisIntoTheSameRead() {
        PageRequest expected = PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "publishedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        when(itemRepository.findByLocationIdAndWithdrawnFalse(NEIGHBORHOOD_ID, expected))
                .thenReturn(new PageImpl<>(List.of(), expected, 0));

        service().board(NEIGHBORHOOD_ID, PageRequest.of(0, 20));

        verify(itemRepository).findByLocationIdAndWithdrawnFalse(NEIGHBORHOOD_ID, expected);
    }

    @Test
    void detailShowsTheHonestStatusOfACorrectedItem() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "t", null, "https://s.example/a", NOW, null);
        item.correct("t2", null, "https://s.example/b", "التصحيح", clock);
        NewsPublisher publisher = NewsPublisher.register("وكالة قدسيا", null);
        publisher.approveVerification();
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(publisherRepository.findById(item.getPublisherId())).thenReturn(Optional.of(publisher));

        NewsItemResponse response = service().getNews(item.getId());

        assertThat(response.corrected()).isTrue();
        assertThat(response.correctionNote()).isEqualTo("التصحيح");
        assertThat(response.correctedAt()).isEqualTo(NOW);
        assertThat(response.publisherName()).isEqualTo("وكالة قدسيا");
        assertThat(response.publisherVerified()).isTrue();
        assertThat(response.sourceUrl()).isEqualTo("https://s.example/b");
    }

    @Test
    void detailOfAWithdrawnItemIsTheSame404AsAnUnknownOne() {
        NewsItem item = NewsItem.publish(UUID.randomUUID(), "t", null, "https://s.example/a", NOW, null);
        item.withdraw(clock);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service().getNews(item.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("News item not found");
        verifyNoInteractions(publisherRepository);
    }

    // ------------------------------------------------------------------
    // The module's DoD as a journey (the plan's §1.2 rule): register →
    // verify → publish → correct → withdraw — one orchestration pin over
    // the mocked collaborators, with the honesty markers riding exactly
    // the boundaries the public display consumes.
    // ------------------------------------------------------------------

    @Test
    void theFullJourneyRegisterVerifyPublishCorrectWithdraw() {
        NewsService service = service();

        // 1. The admin registers the outlet — born UNVERIFIED (the honest registry).
        when(publisherRepository.save(any(NewsPublisher.class))).thenAnswer(inv -> inv.getArgument(0));
        NewsPublisherResponse registered = service.createPublisher(publisherRequest());
        ArgumentCaptor<NewsPublisher> outlet = ArgumentCaptor.forClass(NewsPublisher.class);
        verify(publisherRepository).save(outlet.capture());
        assertThat(registered.verificationState()).isEqualTo("UNVERIFIED");
        assertThat(outlet.getValue().getVerificationState()).isEqualTo(NewsPublisherState.UNVERIFIED);

        // 2. The verdict lands the trust mark — the outlet may publish now.
        NewsPublisher publisher = outlet.getValue();
        when(publisherRepository.findById(publisher.getId())).thenReturn(Optional.of(publisher));
        assertThat(service.verifyPublisher(publisher.getId(), true).verificationState())
                .isEqualTo("VERIFIED");

        // 3. The item publishes with its complete attribution (the scoped write).
        when(itemRepository.save(any(NewsItem.class))).thenAnswer(inv -> inv.getArgument(0));
        neighborhoodNode(NEIGHBORHOOD_ID);
        NewsItemResponse published = service.createNews(new NewsItemRequest(
                publisher.getId(), "العنوان", "الملخص", "https://s.example/a",
                NOW.minusSeconds(60), NEIGHBORHOOD_ID));
        assertThat(published.corrected()).isFalse();
        ArgumentCaptor<NewsItem> saved = ArgumentCaptor.forClass(NewsItem.class);
        verify(itemRepository).save(saved.capture());
        NewsItem item = saved.getValue();

        // 4. The correction lands its required marker — the item stays displayed.
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        NewsItemResponse corrected = service.correctNews(item.getId(), correctionRequest(NEIGHBORHOOD_ID));
        assertThat(corrected.corrected()).isTrue();
        assertThat(corrected.correctionNote()).isEqualTo("صُحّح رقم المساحة المذكور");
        assertThat(corrected.publishedAt()).isEqualTo(NOW.minusSeconds(60));

        // 5. The withdrawal hides the item — the public reads answer the same 404.
        service.withdrawNews(item.getId());
        assertThat(item.isWithdrawn()).isTrue();
        assertThat(item.getWithdrawnAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.getNews(item.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
