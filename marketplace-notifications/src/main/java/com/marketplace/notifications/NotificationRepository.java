package com.marketplace.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, RevisionRepository<Notification, UUID, Integer> {

    /**
     * Plan item 2.6: the caller's feed is a PAGED read — the repository's
     * own standard pattern (BookingController/MessagingController ride the
     * same derived-query pagination; Spring Data Commons, Paginating query
     * results). The ordering contract is unchanged: newest first.
     */
    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    /**
     * Plan item 2.6: the unread badge count — a derived COUNT query, the
     * same Spring Data mechanism, over the {@code is_read} flag the
     * {@code markRead()} state machine already owns.
     */
    long countByRecipientIdAndReadIsFalse(UUID recipientId);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): the export's own
     * total order — newest first with the id as the stable secondary key
     * (the ledger's {@code OrderByCreatedAtDescIdDesc} house convention),
     * so tied timestamps keep a deterministic document order. The service
     * surface above keeps its existing ordering contract untouched.
     */
    List<Notification> findAllByRecipientIdOrderByCreatedAtDescIdDesc(UUID recipientId);
}
