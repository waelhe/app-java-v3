package com.marketplace.catalog;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * L39 (realestate systems plan §5 — SEO and structured data): the
 * sitemap.xml builder — on demand, from the clean ACTIVE set, as plain
 * XML with zero dependencies (the plan's "XML بسيط بلا اعتماديات —
 * جافادوك يوثق المعيار").
 *
 * <p><b>The standard (sitemaps.org 0.90, measured and cached):</b> each
 * sitemap file holds "no more than 50,000 URLs and must be no larger
 * than 50MB"; larger sites use a sitemap index whose {@code <loc>}
 * children point at the page sitemaps; {@code <lastmod>} is the W3C
 * Datetime form of the linked page's last modification ("the date the
 * linked page was last modified, not when the sitemap is generated");
 * {@code <loc>} is a full URI under 2,048 characters. The page size is
 * therefore the standard's own cap — a fixed constant, not a knob that
 * a misconfiguration could push past the cap — and the pagination it
 * provides is the response-size bound (the plan's criterion 4).
 *
 * <p><b>The clean ACTIVE set (the L33 seam):</b> {@code status = ACTIVE}
 * AND {@code expires_at} strictly in the future (or absent — the
 * pre-L33 rows' byte-identical contract). This is deliberately stricter
 * than the expiry job's own match window: between the job's half-hour
 * runs a listing whose window has already passed is still ACTIVE in
 * status, and the sitemap must never advertise a page that is about to
 * answer 404 ("الانتهاء يحدد مجموعة ACTIVE النظيفة"). Soft-deleted rows
 * are excluded structurally — {@code @SoftDelete} appends the filter to
 * every derived and declared query.
 *
 * <p><b>Three honest states (never a fabricated URL):</b>
 * <ul>
 *   <li>Capability OFF (no public site origin bound, or a listing path
 *       that cannot vary per listing) → 503 SU-001 through
 *       {@link ServiceUnavailableException} — the same gate family as the
 *       PSP/MAIL/pseudonymization capabilities. The sitemap describes
 *       pages of the PUBLIC SITE origin, which is a deployment fact the
 *       backend cannot invent.</li>
 *   <li>Nothing to enumerate (empty catalog or an out-of-range page) →
 *       404. The sitemaps.org XSDs require at least one {@code <url>} in
 *       a {@code <urlset>} and one {@code <sitemap>} in a
 *       {@code <sitemapindex>}: there is no valid empty sitemap document,
 *       so an honest 404 beats an invalid 200.</li>
 *   <li>One page → the {@code <urlset>} directly; more than one page →
 *       a {@code <sitemapindex>} at the root pointing at
 *       {@code {base}/sitemap.xml?page=N} children.</li>
 * </ul>
 *
 * <p><b>Deterministic pagination (the L32 total-order rule):</b> the
 * enumeration sorts by {@code id} ascending — offset pagination over a
 * total order never duplicates or omits a listing between crawl visits.
 *
 * <p><b>The served media type (a measured choice):</b>
 * {@code application/xml} without a charset parameter, exactly as
 * RFC 7303 prescribes for XML media types ("the charset parameter
 * SHOULD NOT be used… the encoding declaration in the XML prolog
 * governs"). The prolog declares UTF-8 and the document is
 * ASCII-by-construction — {@code <loc>} URIs (RFC 3986: URIs are
 * ASCII/percent-encoded) and ISO-8601 {@code <lastmod>} timestamps —
 * and ASCII is a byte-identical subset of UTF-8, so whatever charset
 * the String converter applies, the bytes satisfy the prolog. The
 * robots.txt sibling is the deliberate contrast: RFC 9309 declares the
 * file UTF-8 with no prolog to govern, so THAT content type carries
 * the charset explicitly (see {@code SeoController}).
 */
@Service
public class SitemapService {

    /** sitemaps.org 0.90: "each Sitemap file … no more than 50,000 URLs". */
    public static final int SITEMAP_PAGE_SIZE = 50_000;

    /**
     * sitemaps.org 0.90: "Sitemap index files may not list more than
     * 50,000 Sitemaps" — the single-index capacity, and with it the
     * layer's designed scale ceiling: 50,000 pages × 50,000 URLs =
     * 2.5 billion clean-ACTIVE listings. Beyond it the capability
     * answers 503 (CodeRabbit round 2 adoption: never an invalid
     * document, never silent truncation) — the multi-level index
     * redesign (one Sitemap: line per root index in robots.txt, the
     * standard's own "You can have more than one Sitemap index file")
     * is the documented escalation path, deliberately not built for a
     * threshold five orders of magnitude beyond this system's envelope
     * (the D-E6 closure-point pattern: qudsya's catalog by multiples,
     * revisited at full-city scale).
     */
    public static final int MAX_INDEX_ENTRIES = 50_000;

    /** The W3C Datetime form of an {@link java.time.Instant} (xsd:dateTime). */
    private static final DateTimeFormatter LASTMOD = DateTimeFormatter.ISO_INSTANT;

    private static final String URLSET_OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">";
    private static final String SITEMAPINDEX_OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">";

    private final ProviderListingRepository listingRepository;
    private final Clock clock;
    private final CatalogProperties catalogProperties;

    public SitemapService(ProviderListingRepository listingRepository,
                          Clock clock,
                          CatalogProperties catalogProperties) {
        this.listingRepository = listingRepository;
        this.clock = clock;
        this.catalogProperties = catalogProperties;
    }

    /**
     * The sitemap document for a request: {@code page == null} is the
     * root (the urlset when it fits one page, the sitemap index when it
     * does not); a positive page number is that page's urlset.
     *
     * <p>The root decision rides a COUNT, never a row fetch (CodeRabbit
     * round 1: a multi-page root must not materialize a 50,000-row page
     * only to read {@code totalPages} and discard it) — rows are fetched
     * only when the response is a urlset.
     */
    @Transactional(readOnly = true)
    public SeoDocument sitemap(Integer page) {
        CatalogProperties.Seo seo = catalogProperties.seo();
        if (seo.capabilityOff()) {
            throw new ServiceUnavailableException(
                    "The sitemap capability is OFF: no public site origin is bound "
                            + "(marketplace.catalog.seo.public-site-base-url) or the listing "
                            + "path template has no {id} placeholder");
        }
        // One "as of" instant per request (CodeRabbit's final advisory,
        // adopted): the root's count and the single-page fetch share it, so
        // a listing cannot expire BETWEEN the two queries and turn a
        // positive count into an empty fetch (a 404 with a lying cause).
        // A multi-page root returns the index before any fetch; child pages
        // take their own instants by design (each request stands alone).
        Instant now = clock.instant();
        if (page == null) {
            long total = listingRepository.countSitemapEntries(ListingStatus.ACTIVE, now);
            if (total == 0) {
                // No valid empty sitemap exists per the XSDs — the honest
                // answer for an empty catalog is 404.
                throw new ResourceNotFoundException("Sitemap", null);
            }
            long pageCount = (total + SITEMAP_PAGE_SIZE - 1) / SITEMAP_PAGE_SIZE;
            if (pageCount > MAX_INDEX_ENTRIES) {
                throw new ServiceUnavailableException(
                        "The clean ACTIVE set exceeds the single sitemap index capacity "
                                + "(50,000 pages x 50,000 URLs = 2.5 billion listings) — beyond the "
                                + "layer's designed scale; multi-level sitemap indexes are the "
                                + "escalation path");
            }
            if (pageCount > 1) {
                return new SeoDocument(indexXml((int) pageCount), MediaType.APPLICATION_XML);
            }
        }
        Pageable pageable = PageRequest.of(page == null ? 0 : page - 1,
                SITEMAP_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
        Page<SitemapEntry> entries = listingRepository.findSitemapEntries(
                ListingStatus.ACTIVE, now, pageable);
        if (entries.getContent().isEmpty()) {
            // An out-of-range page has nothing to enumerate — 404.
            throw new ResourceNotFoundException("Sitemap page", page);
        }
        return new SeoDocument(urlsetXml(entries), MediaType.APPLICATION_XML);
    }

    /** One urlset page: a {@code <url><loc/><lastmod/></url>} per entry. */
    private String urlsetXml(Page<SitemapEntry> entries) {
        StringBuilder xml = new StringBuilder(256 + entries.getNumberOfElements() * 160);
        xml.append(URLSET_OPEN);
        for (SitemapEntry entry : entries) {
            String loc = catalogProperties.seo().listingUrl(entry.id()).orElseThrow();
            xml.append("<url><loc>").append(escape(loc))
                    .append("</loc><lastmod>")
                    .append(LASTMOD.format(entry.updatedAt()))
                    .append("</lastmod></url>");
        }
        return xml.append("</urlset>").toString();
    }

    /** The index: one child {@code <loc>} per page sitemap. */
    private String indexXml(int totalPages) {
        StringBuilder xml = new StringBuilder(128 + totalPages * 96);
        xml.append(SITEMAPINDEX_OPEN);
        for (int page = 1; page <= totalPages; page++) {
            String loc = catalogProperties.seo().sitemapIndexChildUrl(page).orElseThrow(
                    () -> new IllegalStateException("the capability gate already stood"));
            xml.append("<sitemap><loc>").append(escape(loc))
                    .append("</loc></sitemap>");
        }
        return xml.append("</sitemapindex>").toString();
    }

    /**
     * XML escaping for the URL text nodes — the inputs are
     * configuration-sourced and UUID-bearing (no user text ever enters
     * the sitemap), so this is defense in depth, not a data path.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** The served document: the body plus its media type. */
    public record SeoDocument(String body, MediaType mediaType) {
    }
}
