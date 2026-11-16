package com.marketplace.knowledge;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * D-3 (JT-19/D-30 — «أخبار محلية»): the news display surface — the
 * board (paginated, newest-published first on the service-owned
 * {@code (published_at, id)} complete key, the location axis optional,
 * withdrawn items never appearing, corrected items riding their
 * REQUIRED marker — the honest display, AC-20-09/AC-20-10) and the
 * detail (the honest status: publisher, original link, original date,
 * the correction when it exists; a withdrawn item reads as the same
 * 404).
 *
 * <p><b>Security note:</b> this is the PUBLIC read surface of the wave
 * (no ownership, no /me scoping — the service speaks records only); the
 * {@code permitAll} GET carve-out in {@code SecurityConfig} rides the
 * wave's app-side task (the jobs/institutions precedent — this module's
 * file list cannot carry that file). Until it lands, the reads fall to
 * the {@code anyRequest().authenticated()} blanket exactly as the
 * knowledge board's own GETs do today.</p>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/news")
    @Operation(summary = "The local news board",
            description = "Every live news item from VERIFIED publishers — paginated, newest-published "
                    + "first with the deterministic order, the neighborhood axis optional. Withdrawn "
                    + "items never appear; corrected items ride their honest marker (the note and the "
                    + "timestamp) — the correction is displayed, never the muting (AC-20-09/AC-20-10).")
    public ResponseEntity<PagedResponse<NewsItemResponse>> board(
            @Parameter(description = "The optional neighborhood geo node id (level 3) — omitted returns the whole board.")
            @RequestParam(required = false) UUID locationId,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(newsService.board(locationId, pageable)));
    }

    @GetMapping("/news/{id}")
    @Operation(summary = "Read one news item",
            description = "One item's honest status — the publisher (name + trust mark), the original "
                    + "link, the original date, and the correction marker when it exists "
                    + "(AC-20-09/AC-20-10). A withdrawn item is indistinguishable from an unknown one: "
                    + "the same 404.")
    public ResponseEntity<NewsItemResponse> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(newsService.getNews(id));
    }
}
