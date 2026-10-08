package com.marketplace.knowledge;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * B-14 (compliance plan C.4 — the «تعرف على» guide): the knowledge
 * surface, on the {@code LeadsController}/{@code NeighborhoodPostController}
 * house shapes — the contribution (201, born published), the
 * neighborhood board and the full-text discovery (the composed
 * text+category query — the R6 lesson), the detail, the contributor's
 * own list, the author's revision, and the withdrawal (204, the soft
 * delete).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/knowledge")
    @Operation(summary = "Contribute a knowledge entry",
            description = "The calling member contributes one piece of the neighborhood guide — "
                    + "born published on the open board, with the indexing fact published on the same "
                    + "transaction (the search integration's upsert signal). The locationId gate answers "
                    + "404 for an unknown geo node and 400 for a non-neighborhood node before any write.")
    public ResponseEntity<KnowledgeEntryResponse> contribute(
            @Valid @RequestBody KnowledgeEntryRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeEntryResponse.from(knowledgeService.contribute(request, authentication)));
    }

    @GetMapping("/knowledge")
    @Operation(summary = "The neighborhood guide board",
            description = "One neighborhood's community-built guide — paginated, newest first with "
                    + "the deterministic order, the category axis optional.")
    public ResponseEntity<PagedResponse<KnowledgeEntryResponse>> board(
            @Parameter(description = "The neighborhood's geo node id (level 3).")
            @RequestParam UUID locationId,
            @Parameter(description = "PLACES, SERVICES, HISTORY, PEOPLE or TIPS — omitted returns the whole guide.")
            @RequestParam(required = false) KnowledgeCategory category,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                knowledgeService.board(locationId, category, pageable)
                        .map(KnowledgeEntryResponse::from)));
    }

    @GetMapping("/knowledge/search")
    @Operation(summary = "Full-text discovery over the guide",
            description = "The official websearch parser over every entry's title+body — the same "
                    + "tokenizer the database's GIN index carries — with the category axis composed "
                    + "into the same query (a text query never drops a riding filter), ranked by "
                    + "relevance with the deterministic tiebreak.")
    public ResponseEntity<PagedResponse<KnowledgeEntryResponse>> search(
            @Parameter(description = "The raw user query — the official websearch_to_tsquery parser accepts it as-is.", example = "مسجد الحي")
            @RequestParam String q,
            @Parameter(description = "PLACES, SERVICES, HISTORY, PEOPLE or TIPS — optional.")
            @RequestParam(required = false) KnowledgeCategory category,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                knowledgeService.search(q, category, pageable)
                        .map(KnowledgeEntryResponse::from)));
    }

    @GetMapping("/knowledge/{id}")
    @Operation(summary = "Read one knowledge entry",
            description = "Any live entry by id — the guide is public; unknown is 404.")
    public ResponseEntity<KnowledgeEntryResponse> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(KnowledgeEntryResponse.from(knowledgeService.getEntry(id)));
    }

    @GetMapping("/knowledge/me")
    @Operation(summary = "List my contributions",
            description = "The calling member's own entries — their pieces of the guide, newest first.")
    public ResponseEntity<PagedResponse<KnowledgeEntryResponse>> myEntries(
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                knowledgeService.myEntries(authentication, pageable)
                        .map(KnowledgeEntryResponse::from)));
    }

    @PatchMapping("/knowledge/{id}")
    @Operation(summary = "Revise a knowledge entry (the author's own)",
            description = "The complete new content re-submits — the revision republishes the "
                    + "indexing fact so the discovery surface reflects the revised text. A foreign "
                    + "entry is a 404.")
    public ResponseEntity<KnowledgeEntryResponse> revise(
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeEntryRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(KnowledgeEntryResponse.from(
                knowledgeService.revise(id, request, authentication)));
    }

    @DeleteMapping("/knowledge/{id}")
    @Operation(summary = "Withdraw a knowledge entry (the author's own)",
            description = "The soft delete — the row keeps its audit trail, the reads stop returning "
                    + "it, and the drop signal publishes so the discovery surface never serves a "
                    + "withdrawn entry. A foreign entry is a 404.")
    public ResponseEntity<Void> withdraw(
            @PathVariable UUID id, Authentication authentication) {
        knowledgeService.withdraw(id, authentication);
        return ResponseEntity.noContent().build();
    }
}
