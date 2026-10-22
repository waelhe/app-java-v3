package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots, packet agent-2): the bounded
 * collection projection the catalog owner exposes at the GraphQL
 * boundary. Only facts the {@code collections} row owns travel here: the
 * native {@code id}, the rendered display {@code name} (the bilingual
 * pair's own rendering law — {@code nameEn} when present, else
 * {@code nameAr}), the bilingual second name, the stable public
 * {@code slug}, and the row's own {@code published} fact (the
 * include-unpublished read surface's own gate). Unowned Saleor fields
 * (the metadata family, SEO fields, rich-text descriptions,
 * {@code externalReference}, the channel family, backgroundImage) stay
 * undeclared at the boundary until an owning capability exists — never
 * faked.
 *
 * @param id        the native {@code collections} primary key
 * @param name      the rendered display name (null only when the row
 *                  carries neither bilingual name)
 * @param nameAr    the bilingual second name (null when unset)
 * @param slug      the stable public slug
 * @param published the row's own publication fact
 */
public record CollectionView(UUID id, String name, String nameAr, String slug, boolean published) {
}
