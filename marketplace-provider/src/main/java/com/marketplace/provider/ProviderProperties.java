package com.marketplace.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * W2 (yelp-level plan §5 — the business page): the provider module's
 * type-safe configuration — the module-local nested-record realization of
 * the {@code MarketplaceProperties} pattern (the L33 precedent: provider
 * rides platform-infra, but a module-local record keeps the module's
 * configuration concern inside the module — the same shape
 * {@code CatalogProperties} settled for the catalog module).
 *
 * <p><b>The {@code seo} section — the public-site origin pairing:</b> the
 * JSON-LD block the business page emits needs the page's own public URL.
 * The public site is ONE deployment fact; the catalog module binds it as
 * {@code marketplace.catalog.seo.public-site-base-url} for the LISTING
 * pages (the Modulith dependency law keeps that record inside catalog —
 * the L33 measured decision), and this record binds the same deployment
 * fact for the PROVIDER pages. The deployment pairs the two keys to the
 * same origin (the Railway env carries one value for both); a mismatch is
 * an operator error the honesty rule below already contains: when blank,
 * the JSON-LD block simply omits {@code url} (a fabricated URL helps no
 * one — the L39 convention).
 */
@ConfigurationProperties(prefix = "marketplace.provider")
public record ProviderProperties(
        @DefaultValue Seo seo
) {

    /**
     * The provider pages' SEO section — the same shape
     * {@code CatalogProperties.Seo} settled (greptile r3, adopted): a
     * NON-BLANK public-site base must be an absolute http(s) URI,
     * validated at binding time (the D6 fail-fast posture — a malformed
     * origin is an operator error that must stop startup with a readable
     * message, never reach traffic).
     */
    public record Seo(
            @DefaultValue("") String publicSiteBaseUrl,
            @DefaultValue("/providers/{id}") String providerPath
    ) {

        public Seo {
            if (publicSiteBaseUrl != null && !publicSiteBaseUrl.isBlank()) {
                URI bound;
                try {
                    bound = URI.create(publicSiteBaseUrl.trim());
                } catch (IllegalArgumentException malformed) {
                    throw new IllegalArgumentException(
                            "marketplace.provider.seo.public-site-base-url is not a valid URI: "
                                    + publicSiteBaseUrl, malformed);
                }
                if (bound.getScheme() == null || bound.getHost() == null
                        || (!"http".equalsIgnoreCase(bound.getScheme())
                            && !"https".equalsIgnoreCase(bound.getScheme()))) {
                    throw new IllegalArgumentException(
                            "marketplace.provider.seo.public-site-base-url is not a valid URI: "
                                    + publicSiteBaseUrl);
                }
            }
            if (providerPath == null || !providerPath.contains("{id}")
                    || !providerPath.startsWith("/")) {
                throw new IllegalArgumentException(
                        "marketplace.provider.seo.provider-path must be an absolute path "
                                + "containing the {id} placeholder: " + providerPath);
            }
        }

        /**
         * The provider page's public URL — empty when the capability is OFF
         * (blank origin: the JSON-LD {@code url} field is omitted, never
         * fabricated — the L39 honesty rule).
         */
        public java.util.Optional<String> providerUrl(java.util.UUID providerId) {
            if (publicSiteBaseUrl == null || publicSiteBaseUrl.isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(publicSiteBaseUrl.trim()
                    + providerPath.replace("{id}", providerId.toString()));
        }
    }
}
