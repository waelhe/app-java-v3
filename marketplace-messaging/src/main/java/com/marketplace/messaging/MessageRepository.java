package com.marketplace.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID>, RevisionRepository<Message, UUID, Integer> {

    Page<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): every live message
     * the user SENT (the plan's provenance rule — his authored content
     * only), in creation order for a deterministic export — backs
     * {@code MessagingExportAdapter}.
     */
    List<Message> findAllBySenderIdOrderByCreatedAtAscIdAsc(UUID senderId);

    /**
     * B-03 (compliance plan 0.3 — the measured defect §3.4-2): the unread
     * badge counts only what the CALLER still needs to read — the caller's
     * own sent messages are excluded, the same sender-exclusion discipline
     * {@link #markAsReadByConversationId} already carries (a derived query
     * method per the Data JPA reference, Query Methods).
     */
    long countByConversationIdAndSenderIdNotAndReadFalse(UUID conversationId, UUID senderId);

    /**
     * B-04 (compliance plan 0.4): the send's replay lookup — the caller's
     * deduplication surface, ONE KEY SPACE PER SENDER (the CodeRabbit
     * round-1 root adoption: a client-chosen key is realistic to collide
     * across senders, so the scoping and the V150
     * uq_messages_sender_idempotency_key constraint share the same
     * (sender_id, idempotency_key) shape — the constraint's own index
     * serves this lookup). The sequential retry is answered here; the
     * in-flight race is answered by the service's catch-and-replay over
     * the same lookup in a fresh transaction.
     */
    Optional<Message> findBySenderIdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    /**
     * Bulk mark unread messages as read for a specific conversation (excluding sender's own messages).
     * Uses @Modifying for efficient UPDATE without loading entities into memory.
     */
    @Modifying
    @Query("UPDATE Message m SET m.read = true WHERE m.conversationId = :conversationId AND m.read = false AND m.senderId <> :userId")
    int markAsReadByConversationId(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId);
}
