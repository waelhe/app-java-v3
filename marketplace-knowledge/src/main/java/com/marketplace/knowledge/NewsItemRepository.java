package com.marketplace.knowledge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * D-3 (JT-19/D-30): the news board's repository — the derived-query half
 * of the house's mixed discipline (no full-text path in this wave — the
 * board is the surface; a text query follows the V9/V156 pattern when a
 * decision opens it). The withdrawal is a DOMAIN flag, so every read
 * carries {@code withdrawn = false} EXPLICITLY (the @SoftDelete filter
 * covers {@code is_deleted} only); the SERVICE passes every sort (the
 * L32/D-N5 lesson — the client's Pageable never picks the order, the
 * {@code (published_at, id)} complete key keeps the page boundary
 * deterministic and is the exact shape the V179 board index carries).
 */
public interface NewsItemRepository extends JpaRepository<NewsItem, UUID> {

    /** The whole-city board: every live, non-withdrawn item, newest-published first. */
    Page<NewsItem> findByWithdrawnFalse(Pageable pageable);

    /** The neighborhood-scoped board: the optional location axis composed into the same read. */
    Page<NewsItem> findByLocationIdAndWithdrawnFalse(UUID locationId, Pageable pageable);
}
