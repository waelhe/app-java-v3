package com.marketplace.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D-3 (JT-19/D-30): the two REST surfaces' statuses — the direct
 * controller-invocation house pattern: 201 on the outlet's registration
 * and the publication, 200 on the verdict, the correction and the reads,
 * 204 on the withdrawal, the paged envelope riding {@code PagedResponse}.
 * The controllers speak the data-minimized records only (the ArchUnit
 * HTTP-boundary rule) — the service hands them the assembled views.
 */
@ExtendWith(MockitoExtension.class)
class NewsControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private NewsService service;

    @InjectMocks
    private NewsAdminController adminController;

    @InjectMocks
    private NewsController controller;

    private NewsPublisherResponse publisherResponse() {
        return new NewsPublisherResponse(UUID.randomUUID(), "وكالة قدسيا للأنباء",
                "https://qudsayya-news.example", "VERIFIED", NOW, NOW);
    }

    private NewsPublisherResponse unverifiedPublisherResponse() {
        return new NewsPublisherResponse(UUID.randomUUID(), "وكالة قدسيا للأنباء",
                "https://qudsayya-news.example", "UNVERIFIED", NOW, NOW);
    }

    private NewsItemResponse itemResponse() {
        return new NewsItemResponse(UUID.randomUUID(), UUID.randomUUID(), "وكالة قدسيا للأنباء", true,
                "افتتاح الطريق الدائري الجديد", "ملخص الخبر", "https://qudsayya-news.example/road",
                NOW.minusSeconds(3600), null, false, null, null);
    }

    // ------------------------------------------------------------------
    // The admin surface (NewsAdminController).
    // ------------------------------------------------------------------

    @Test
    void createPublisherAnswers201() {
        var request = new NewsPublisherRequest("وكالة قدسيا للأنباء", "https://qudsayya-news.example");
        when(service.createPublisher(request)).thenReturn(unverifiedPublisherResponse());

        ResponseEntity<NewsPublisherResponse> result = adminController.createPublisher(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().name()).isEqualTo("وكالة قدسيا للأنباء");
        assertThat(result.getBody().verificationState()).isEqualTo("UNVERIFIED");
    }

    @Test
    void verifyPublisherAnswers200() {
        UUID publisherId = UUID.randomUUID();
        when(service.verifyPublisher(publisherId, true)).thenReturn(publisherResponse());

        ResponseEntity<NewsPublisherResponse> result =
                adminController.verifyPublisher(publisherId, "APPROVE");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().verificationState()).isEqualTo("VERIFIED");
    }

    @Test
    void createNewsAnswers201() {
        var request = new NewsItemRequest(UUID.randomUUID(), "العنوان", "الملخص",
                "https://qudsayya-news.example/road", NOW.minusSeconds(3600), null);
        when(service.createNews(request)).thenReturn(itemResponse());

        ResponseEntity<NewsItemResponse> result = adminController.createNews(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().publisherName()).isEqualTo("وكالة قدسيا للأنباء");
        assertThat(result.getBody().sourceUrl()).isEqualTo("https://qudsayya-news.example/road");
    }

    @Test
    void correctNewsAnswers200() {
        UUID itemId = UUID.randomUUID();
        var request = new NewsItemCorrectionRequest("العنوان المصحح", null,
                "https://qudsayya-news.example/road-v2", null, "صُحّح رقم المساحة");
        when(service.correctNews(itemId, request)).thenReturn(itemResponse());

        ResponseEntity<NewsItemResponse> result = adminController.correctNews(itemId, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withdrawNewsAnswers204() {
        ResponseEntity<Void> result = adminController.withdrawNews(UUID.randomUUID());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ------------------------------------------------------------------
    // The public display surface (NewsController).
    // ------------------------------------------------------------------

    @Test
    void boardAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.board(null, pageable))
                .thenReturn(new PageImpl<>(List.of(itemResponse()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<NewsItemResponse>> result =
                controller.board(null, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void boardComposesTheOptionalLocationAxis() {
        UUID locationId = UUID.randomUUID();
        var pageable = PageRequest.of(0, 20);
        when(service.board(locationId, pageable))
                .thenReturn(new PageImpl<>(List.of(itemResponse()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<NewsItemResponse>> result =
                controller.board(locationId, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void detailAnswers200() {
        NewsItemResponse response = itemResponse();
        when(service.getNews(response.id())).thenReturn(response);

        ResponseEntity<NewsItemResponse> result = controller.detail(response.id());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().id()).isEqualTo(response.id());
        assertThat(result.getBody().title()).isEqualTo("افتتاح الطريق الدائري الجديد");
    }
}
