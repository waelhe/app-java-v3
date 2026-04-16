package com.marketplace.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Page<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    long countByConversationIdAndReadFalse(UUID conversationId);

    /**
     * Bulk mark unread messages as read for a specific conversation (excluding sender's own messages).
     * Uses @Modifying for efficient UPDATE without loading entities into memory.
     */
    @Modifying
    @Query("UPDATE Message m SET m.read = true WHERE m.conversationId = :conversationId AND m.read = false AND m.senderId <> :userId")
    int markAsReadByConversationId(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId);
}
