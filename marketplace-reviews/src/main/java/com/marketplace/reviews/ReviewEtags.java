package com.marketplace.reviews;

import com.marketplace.shared.api.PagedResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * B-09 (compliance plan B.3 — Framework reference/web/webmvc/mvc-caching.html):
 * the conditional-GET validators for the reviews read surface. The ETag is
 * a content fingerprint — MD5 over the response's OWN material (the record's
 * complete toString: every visible field, including the batch-resolved
 * blocks a row-stamp alone would miss — reviewer name/count, helpful votes,
 * the provider reply) — so a 304 means EXACTLY "what you would get is what
 * you had", by construction rather than by field enumeration. The same
 * hashing discipline as the framework's own ShallowEtagHeaderFilter
 * ("a shallow ETag... based on the response content"), computed from the
 * content keys before serialization: the 304 answer skips rendering and
 * transfer, and the offline client (edge matrix row 7) revalidates its
 * cache with one round trip.
 *
 * <p>Tags are STRONG (deterministic material — the same content always
 * hashes identically) and the values are passed UNQUOTED to
 * {@code WebRequest.checkNotModified} (the method applies HTTP quoting
 * itself when comparing and when setting the response header).
 */
final class ReviewEtags {

    private ReviewEtags() {
    }

    /** The single-review read's validator — the full response material. */
    static String forReview(ReviewResponse review) {
        return value("review|" + review);
    }

    /**
     * The list reads' validator — scope + page window + the full material
     * of every row in the window. A change to any row IN the page, to the
     * page window's composition (an insert shifts the newest-first order),
     * or to the result-set size (a soft delete) changes the tag; a change
     * strictly OUTSIDE the page does not — and correctly so, because this
     * page's answer is genuinely unchanged.
     */
    static String forPage(String scope, Object scopeId, PagedResponse<ReviewResponse> page) {
        return value(scope + "|" + scopeId + "|" + page.pageNumber() + "|" + page.pageSize()
                + "|" + page.totalElements() + "|" + page.content());
    }

    private static String value(String material) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed by the JDK specification — unreachable.
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
