package com.marketplace.notifications;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, RevisionRepository<Notification, UUID, Integer> {
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): the export's own
     * total order — newest first with the id as the stable secondary key
     * (the ledger's {@code OrderByCreatedAtDescIdDesc} house convention),
     * so tied timestamps keep a deterministic document order. The service
     * surface above keeps its existing ordering contract untouched.
     */
    List<Notification> findAllByRecipientIdOrderByCreatedAtDescIdDesc(UUID recipientId);
}
