package com.marketplace.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 7 (ADR-0003): the push token registry's reads — the dispatch's
 * addressing (the user's live tokens) and the upsert's lookup (by the
 * token itself, the UNIQUE key).
 */
public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {

    Optional<PushToken> findByToken(String token);

    List<PushToken> findByUserId(UUID userId);

    void deleteByUserIdAndToken(UUID userId, String token);
}
