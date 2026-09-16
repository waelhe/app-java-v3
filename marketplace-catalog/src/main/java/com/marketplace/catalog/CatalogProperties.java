package com.marketplace.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L33 (realestate systems plan §5): the classifieds lifecycle policy —
 * the MediaProperties pattern (module-owned, type-safe, constructor
 * binding, primed sections per the AGENTS.md binding rule).
 *
 * <p>{@code expiryDays}: NULL by design (no code default) — a deployment
 * that wants listings to expire MUST declare the policy (yml or
 * {@code MARKETPLACE_CATALOG_EXPIRY_DAYS}); with the property unset, an
 * activation without an explicit date answers 409 ("no silently-immortal
 * listing" — the acceptance criterion). The house yml declares 90 (the
 * global classifieds convention range).
 *
 * <p>{@code renewalCooldownDays}: the anti-recycling floor — at most one
 * renewal per window per listing (default 1: a same-day second renewal is
 * refused with 409). Always-on (a safe default), env-tunable.
 *
 * <p>L39 (realestate systems plan §5 — SEO and structured data): the
 * {@code seo} section, the same module-local nested-record realization of
 * the MarketplaceProperties pattern the expiry section used (catalog is
 * shared-only under Modulith — the platform-infra class is not on its
 * allowed dependencies, and the L33 precedent settled the shape).
 *
 * <p>L40 (realestate systems plan §5 — view analytics): the {@code views}
 * section — the visitor-fingerprint key and the dedup window. Same shape,
 * same rules: the HMAC key is blank by default, REQUIRED under the prod
 * profile (CatalogConfig fails startup — the MessagingConfig pattern), and
 * the test profile declares a known key (application-test.yml). The dedup
 * window defaults to the plan's "Redis TTL يوم" (24h) and stays
 * env-tunable so the TTL-expiry acceptance criterion can run in
 * milliseconds instead of a day.
 */
@ConfigurationProperties(prefix = "marketplace.catalog")
public record CatalogProperties(
        @DefaultValue Expiry expiry,
        @DefaultValue Seo seo,
        @DefaultValue Views views
) {

    /**
     * L40: the view-analytics policy.
     *
     * <p>{@code ipHashKey}: the HmacSHA256 key for the visitor fingerprint
     * (the L34 leads pattern, CWE-759 — the IPv4 space is enumerable, so a
     * bare digest is reversible by brute force; a keyed HMAC is not). The
     * fingerprint NEVER leaves the process: it exists solely as part of the
     * ephemeral Redis dedup key (24h TTL), so no permanent identifier is
     * stored for the anonymous visitor (the plan's privacy-by-design —
     * no new b-5). Blank by default; CatalogConfig fails prod startup when
     * blank; the test profile pins a known value.
     *
     * <p>{@code dedupWindow}: how long one (visitor, listing) pair counts
     * as ONE visit — the plan's "زيارة واحدة لكل (زائر/لوحة/يوم) بمعيار
     * بصمة الزائر في الذاكرة (Redis TTL يوم)". Fixed expiry from the FIRST
     * view (SET .. EX — no sliding renewal), so a visitor is counted at
     * most once per rolling 24h, not per calendar day: the plan's own
     * documented choice. Default 24h; tests override to milliseconds to
     * prove the marker actually disappears (acceptance criterion 3).
     */
    public record Views(
            @DefaultValue("") String ipHashKey,
            @DefaultValue("24h") Duration dedupWindow
    ) {
    }

    public record Expiry(
            /** Days a listing's publication lasts — null = policy unset. */
            Integer expiryDays,
            /** Minimum days between two renewals of one listing. */
            @DefaultValue("1") Integer renewalCooldownDays
    ) {
    }

    /**
     * L39: the public-site contract of the SEO surfaces. The backend
     * serves the sitemap and the JSON-LD <em>data</em>; the pages they
     * describe belong to the frontend origin, so the origin is a
     * deployment fact, never a code constant.
     *
     * <p>{@code publicSiteBaseUrl}: the PUBLIC SITE origin (no trailing
     * slash — normalized defensively), e.g. {@code https://qudsya.example}.
     * Blank by design (the same gate model as the PSP/MAIL/pseudonymization
     * capabilities): {@code GET /sitemap.xml} answers 503 SU-001 ("the
     * capability is OFF, not broken"), the JSON-LD block omits {@code url},
     * and robots.txt omits the {@code Sitemap:} line — every one of them
     * rather than inventing a URL that resolves nowhere. The frontend's
     * hosting layer (client-hosting strategy plan, gates B/C) owns the
     * real origin; when it exists, one env variable turns the capability
     * on with no code change.
     *
     * <p>{@code listingPath}: the public site's listing-page path template
     * containing the {@code {id}} placeholder (default {@code /listings/{id}}
     * — the natural REST page shape). A template without the placeholder
     * cannot vary per listing, so the capability gate treats it exactly
     * like a blank base URL: OFF, not half-working.
     *
     * <p>{@code robotsDisallowPaths}: the robots.txt (RFC 9309)
     * {@code Disallow} path list — each MUST start with {@code /}
     * (non-conforming entries are skipped defensively, never invented).
     * Empty by default: the public site allows all crawlers.
     */
    public record Seo(
            @DefaultValue("") String publicSiteBaseUrl,
            @DefaultValue("/listings/{id}") String listingPath,
            @DefaultValue List<String> robotsDisallowPaths
    ) {

        /** The normalized origin — blank when the capability is off. */
        private String normalizedBase() {
            if (publicSiteBaseUrl == null || publicSiteBaseUrl.isBlank()) {
                return "";
            }
            String trimmed = publicSiteBaseUrl.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }

        /**
         * True when the SEO surfaces cannot honestly serve: no public
         * origin bound, or a listing path that cannot vary per listing.
         * The 503-family gate — never a fabricated URL.
         */
        public boolean capabilityOff() {
            return normalizedBase().isEmpty() || listingPath == null || !listingPath.contains("{id}");
        }

        /**
         * The absolute listing page URL for the sitemap and the JSON-LD
         * {@code url} field — empty when the capability is off.
         */
        public Optional<String> listingUrl(UUID listingId) {
            if (capabilityOff()) {
                return Optional.empty();
            }
            String path = listingPath.contains("{id}")
                    ? listingPath.replace("{id}", listingId.toString())
                    : listingPath;
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return Optional.of(normalizedBase() + path);
        }

        /**
         * The sitemap index child URL of one page sitemap — empty when
         * the capability is off. The children of a {@code <sitemapindex>}
         * are absolute URIs exactly like the urlset's {@code <loc>}
         * entries (the same tLoc shape in the standard's XSDs).
         */
        public Optional<String> sitemapIndexChildUrl(int page) {
            if (capabilityOff()) {
                return Optional.empty();
            }
            return Optional.of(normalizedBase() + "/sitemap.xml?page=" + page);
        }

        /**
         * The sitemap's own absolute URL — the robots.txt
         * {@code Sitemap:} line (the sitemaps.org convention). Empty when
         * the capability is off (the line is omitted, never a guess).
         */
        public Optional<String> sitemapUrl() {
            if (capabilityOff()) {
                return Optional.empty();
            }
            return Optional.of(normalizedBase() + "/sitemap.xml");
        }

        /**
         * The robots.txt {@code Disallow} lines' source — only entries
         * that start with {@code /} (RFC 9309: a path-pattern starts with
         * the first octet of the path); a non-conforming entry would
         * poison the whole file for strict parsers, so it is skipped.
         */
        public List<String> conformingDisallowPaths() {
            if (robotsDisallowPaths == null) {
                return List.of();
            }
            return robotsDisallowPaths.stream()
                    .filter(path -> path != null && path.startsWith("/"))
                    .toList();
        }
    }
}
