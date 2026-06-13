package com.marketplace.messaging;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID>, RevisionRepository<Conversation, UUID, Integer> {

    Optional<Conversation> findByBookingId(UUID bookingId);
}