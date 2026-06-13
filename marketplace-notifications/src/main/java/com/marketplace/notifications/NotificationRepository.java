package com.marketplace.notifications;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, RevisionRepository<Notification, UUID, Integer> {
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);
}
