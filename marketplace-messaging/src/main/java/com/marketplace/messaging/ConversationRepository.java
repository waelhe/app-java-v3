package com.marketplace.messaging;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID>, RevisionRepository<Conversation, UUID, Integer> {

    Optional<Conversation> findByBookingId(UUID bookingId);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): every live
     * conversation the user participates in (either side), in creation
     * order for a deterministic export — backs
     * {@code MessagingExportAdapter}.
     */
    List<Conversation> findAllByParticipantAOrParticipantBOrderByCreatedAtAscIdAsc(
            UUID participantA, UUID participantB);
}