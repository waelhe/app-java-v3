package com.marketplace.messaging;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID>, RevisionRepository<Conversation, UUID, Integer> {

    Optional<Conversation> findByBookingId(UUID bookingId);

    /**
     * L44 (neighborhood community plan §5 — direct neighbor messages): the
     * existing direct conversation for one participant pair, if any. The
     * service always stores the pair in canonical {@code UUID.compareTo}
     * order ({@code participantA = min}, {@code participantB = max}), so a
     * plain pair equality is the pair's identity regardless of which side
     * opened it — and it never sees a booking conversation by construction
     * ({@code booking_id IS NULL} is the direct marker, D-N8). Rides the
     * V7 {@code idx_conversations_participants} partial index; the V67
     * {@code (LEAST, GREATEST)} partial unique index is the same
     * invariant's DB-level backstop.
     */
    Optional<Conversation> findFirstByBookingIdIsNullAndParticipantAAndParticipantB(
            UUID participantA, UUID participantB);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): every live
     * conversation the user participates in (either side), in creation
     * order for a deterministic export — backs
     * {@code MessagingExportAdapter}.
     */
    List<Conversation> findAllByParticipantAOrParticipantBOrderByCreatedAtAscIdAsc(
            UUID participantA, UUID participantB);
}