package com.marketplace.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    long countByConversationIdAndReadFalse(UUID conversationId);

    /**
     * Bulk mark unread messages as read for a specific conversation (excluding sender's own messages).
     * Uses @Modifying for efficient UPDATE without loading entities into memory.
     */
    @Modifying
    @Query("UPDATE Message m SET m.read = true WHERE m.conversationId = :conversationId AND m.read = false AND m.senderId <> :userId")
    int markAsReadByConversationId(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId);
}
