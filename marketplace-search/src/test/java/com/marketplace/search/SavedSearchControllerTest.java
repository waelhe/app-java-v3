package com.marketplace.search;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the
 * controller seams — the "me" owner resolution (the plain /me seam), the
 * criteria echo, and the status codes the surface promises. The security
 * negatives (401 anonymous, the authenticated-but-invalid 400) live in
 * the real-chain integration test, the house split.
 */
@ExtendWith(MockitoExtension.class)
class SavedSearchControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private SavedSearchService savedSearchService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private SavedSearchController controller;

    @Test
    void createAnswers201AndEchoesTheCriteriaNode() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        var node = mapper.readTree("{\"minRooms\":2,\"category\":\"stay\"}");
        SavedSearch stored = SavedSearch.create(UUID.randomUUID(), USER_ID,
                new com.marketplace.shared.api.SearchCriteria(null, "stay", null, null), true);
        when(savedSearchService.create(eq(USER_ID), eq(node), eq(true))).thenReturn(stored);

        ResponseEntity<SavedSearchController.SavedSearchView> response =
                controller.create(new SavedSearchController.SavedSearchCreateRequest(node, true),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(stored.getId());
        assertThat(response.getBody().criteria()).isSameAs(node); // the verbatim echo
        assertThat(response.getBody().alertEnabled()).isTrue();
    }

    @Test
    void listKeysThePageByTheCallersUserId() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        Pageable pageable = PageRequest.of(0, 10);
        SavedSearch stored = SavedSearch.create(UUID.randomUUID(), USER_ID,
                new com.marketplace.shared.api.SearchCriteria(null, "stay", null, null), false);
        when(savedSearchService.listFor(USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(stored)));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<SavedSearchController.SavedSearchView>> response =
                controller.list(pageable, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).hasSize(1);
        // the stored record re-serializes through the same round-trip the
        // matcher trusts — the view carries the criteria node back
        assertThat(response.getBody().content().get(0).criteria().toString())
                .contains("stay");
    }

    @Test
    void deleteDelegatesWithTheOwnerKey() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(id, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(savedSearchService).delete(USER_ID, id);
    }
}
