package com.marketplace.catalog;

import com.marketplace.shared.api.BadRequestException;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * L39 (realestate systems plan §5 — SEO and structured data): the two
 * crawler surfaces, at the root paths the standards themselves fix —
 * robots.txt "MUST be located … in the top-level path" (RFC 9309) and
 * the sitemap is {@code /sitemap.xml} by universal convention
 * (sitemaps.org). Both are public GETs on the resource-server chain
 * with precise {@code permitAll} lines (the L34/L36 precedent — the
 * SecurityConfig matcher carries the two root paths alongside the API
 * prefixes, so they never fall to the form-login default chain).
 *
 * <p><b>Ownership (the plan's boundary):</b> the backend serves the
 * DATA — the sitemap enumerates the clean ACTIVE set and robots.txt is
 * the configured policy; the HTML pages they describe belong to the
 * public site origin (the client-hosting plan's frontend), which is
 * why every URL is built from {@code marketplace.catalog.seo}
 * configuration, never from this API's own origin.
 *
 * <p><b>Rate limiting (the plan's criterion 4):</b> the sitemap rides
 * the {@code seo} named limiter (fail fast, 429 RL-001 — the
 * geoSuggest model: a crawler's burst must not eat the shared catalog
 * read budget). robots.txt is a pure configuration emission with no
 * data path — no limiter (crawlers refetch it by design before every
 * session; throttling it would break the protocol's own etiquette).
 */
@RestController
public class SeoController {

    private final SitemapService sitemapService;
    private final CatalogProperties catalogProperties;

    public SeoController(SitemapService sitemapService,
                         CatalogProperties catalogProperties) {
        this.sitemapService = sitemapService;
        this.catalogProperties = catalogProperties;
    }

    /**
     * The sitemap: the root returns the single urlset when the clean
     * ACTIVE set fits one page (the sitemaps.org 50,000-URL cap), or the
     * sitemap index when it does not; {@code ?page=N} returns that
     * page's urlset.
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @RateLimiter(name = "seo")
    @Operation(summary = "The listing sitemap (sitemaps.org 0.90)",
            description = "L39: built on demand from the clean ACTIVE set (ACTIVE "
                    + "status and an un-passed expiry window) — one <url> per listing "
                    + "page with its lastmod from the updated_at audit column, "
                    + "paginated by the standard's 50,000-URL cap with a sitemap "
                    + "index at the root when the set spans more than one page. "
                    + "The <loc> URLs describe the PUBLIC SITE origin bound in "
                    + "marketplace.catalog.seo — this API's own origin never appears. "
                    + "503 SU-001 when no public origin is bound (capability OFF, "
                    + "not broken); 404 when there is nothing to enumerate (no valid "
                    + "empty sitemap document exists per the standard's XSDs).")
    public ResponseEntity<String> sitemap(
            @Parameter(description = "The page sitemap to fetch (1-based) — the root "
                    + "serves the index or the single urlset")
            @RequestParam(required = false) Integer page) {
        if (page != null && page < 1) {
            throw new BadRequestException("Sitemap page numbers are 1-based");
        }
        SitemapService.SeoDocument document = sitemapService.sitemap(page);
        return ResponseEntity.ok()
                .contentType(document.mediaType())
                .body(document.body());
    }

    /**
     * robots.txt (RFC 9309): the configured crawl policy — the
     * {@code User-agent: *} group with the configured Disallow paths
     * (an empty list emits the canonical allow-all
     * {@code Disallow:} line), plus the sitemaps.org
     * {@code Sitemap:} pointer when the public origin is bound.
     */
    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "The crawler policy (RFC 9309)",
            description = "L39: plain UTF-8 text/plain at the top-level path the "
                    + "standard fixes. The Disallow lines come from "
                    + "marketplace.catalog.seo.robots-disallow-paths (each must "
                    + "start with /; empty = allow all — the canonical empty "
                    + "Disallow), and the Sitemap line points at the bound public "
                    + "origin's /sitemap.xml. The /robots.txt URI itself is "
                    + "implicitly allowed (RFC 9309).")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(robotsBody());
    }

    /**
     * The RFC 9309 body: one {@code User-agent: *} group, the configured
     * {@code Disallow} lines (or the explicit empty one — the grammar's
     * {@code empty-pattern}, the universally parsed allow-all form), and
     * the {@code Sitemap:} extension line (sitemaps.org — "Tell the
     * crawlers about your sitemap in robots.txt") only when a public
     * origin is bound.
     */
    private String robotsBody() {
        CatalogProperties.Seo seo = catalogProperties.seo();
        StringBuilder body = new StringBuilder(128);
        body.append("User-agent: *\n");
        List<String> disallowPaths = seo.conformingDisallowPaths();
        if (disallowPaths.isEmpty()) {
            body.append("Disallow:\n");
        } else {
            disallowPaths.forEach(path -> body.append("Disallow: ").append(path).append('\n'));
        }
        seo.sitemapUrl().ifPresent(loc -> body.append("Sitemap: ").append(loc).append('\n'));
        return body.toString();
    }
}
