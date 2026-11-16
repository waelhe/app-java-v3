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
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * B-14 (compliance plan C.4): the REST surface's statuses — the direct
 * controller-invocation house pattern: 201 on the contribution, 200 on
 * the reads and the revision, 204 on the withdrawal, the paged
 * envelopes riding {@code PagedResponse}.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private KnowledgeService service;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private KnowledgeController controller;

    private KnowledgeEntry entry() {
        return KnowledgeEntry.contribute(UUID.randomUUID(), UUID.randomUUID(),
                KnowledgeCategory.PLACES, "مسجد الحي", "بُني المسجد قبل خمسين عامًا");
    }

    @Test
    void contributeAnswers201() {
        var request = new KnowledgeEntryRequest(UUID.randomUUID(), KnowledgeCategory.PLACES, "t", "b");
        when(service.contribute(request, authentication)).thenReturn(entry());

        ResponseEntity<KnowledgeEntryResponse> result = controller.contribute(request, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().title()).isEqualTo("مسجد الحي");
        assertThat(result.getBody().category()).isEqualTo("PLACES");
    }

    @Test
    void boardAnswers200WithThePagedEnvelope() {
        UUID locationId = UUID.randomUUID();
        var pageable = PageRequest.of(0, 20);
        when(service.board(locationId, KnowledgeCategory.PLACES, pageable))
                .thenReturn(new PageImpl<>(List.of(entry()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<KnowledgeEntryResponse>> result =
                controller.board(locationId, KnowledgeCategory.PLACES, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void searchAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.search("مسجد الحي", KnowledgeCategory.PLACES, pageable))
                .thenReturn(new PageImpl<>(List.of(entry()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<KnowledgeEntryResponse>> result =
                controller.search("مسجد الحي", KnowledgeCategory.PLACES, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void detailAnswers200() {
        KnowledgeEntry entry = entry();
        when(service.getEntry(entry.getId())).thenReturn(entry);

        ResponseEntity<KnowledgeEntryResponse> result = controller.detail(entry.getId());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().id()).isEqualTo(entry.getId());
        assertThat(result.getBody().title()).isEqualTo("مسجد الحي");
    }

    @Test
    void myEntriesAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.myEntries(authentication, pageable))
                .thenReturn(new PageImpl<>(List.of(entry()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<KnowledgeEntryResponse>> result =
                controller.myEntries(pageable, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void reviseAnswers200() {
        UUID id = UUID.randomUUID();
        var request = new KnowledgeEntryRequest(UUID.randomUUID(), KnowledgeCategory.HISTORY, "t2", "b2");
        KnowledgeEntry revised = entry();
        when(service.revise(id, request, authentication)).thenReturn(revised);

        ResponseEntity<KnowledgeEntryResponse> result = controller.revise(id, request, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withdrawAnswers204() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> result = controller.withdraw(id, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
