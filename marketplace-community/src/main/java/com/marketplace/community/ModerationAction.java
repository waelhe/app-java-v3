package com.marketplace.community;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer):
 * the administrative resolve command's action vocabulary — the plan's own
 * request body {@code {action: DISMISS|HIDE_CONTENT}}.
 *
 * <ul>
 *   <li>{@code DISMISS} — no content action: the report closes
 *       {@code DISMISSED}, the content stays exactly as it is.</li>
 *   <li>{@code HIDE_CONTENT} — the plan's own words: "يقلب
 *       NeighborhoodPost.status إلى HIDDEN_BY_MODERATOR (أو يحذف التعليق
 *       ناعمًا حسب النوع) في نفس المعاملة" — the flip, the report's
 *       {@code RESOLVED} close and the author's {@code CONTENT_MODERATED}
 *       event all land in the resolve command's one transaction.</li>
 * </ul>
 */
public enum ModerationAction {
    DISMISS,
    HIDE_CONTENT
}
