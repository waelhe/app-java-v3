package com.marketplace.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * B-07 (compliance plan 0.8 — the measured defect §3.4-6): the badge's
     * clear-all — one bulk UPDATE over the caller's unread rows, no entity
     * loading (the messaging module's {@code markAsReadByConversationId}
     * @Modifying pattern). Returns the number of rows marked.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipientId = :recipientId AND n.read = false")
    int markAllAsReadByRecipientId(@Param("recipientId") UUID recipientId);

    /**
     * Task 5-f (the delivery-dedup ledger): the replay gate — has THIS
     * recipient already received a notification born from THIS source
     * event? The pre-insert check of the
     * {@code uq_notifications_source_event_once} partial unique index (the
     * V93 provider_follow_alerts ledger discipline): the derived query
     * answers the common sequential re-delivery before any write is
     * attempted, the DB index remains the concurrent-insert backstop.
     */
    boolean existsByRecipientIdAndSourceEventId(UUID recipientId, UUID sourceEventId);


/**
     * The urgent-alert inbox's idempotent insert — the V180 ledger key's
     * own writer, in the V54 saved_search_matches / V199
     * notification_deliveries shape (CodeRabbit's round on the #545
     * slice): {@code INSERT ... ON CONFLICT DO NOTHING}, the returned
     * count IS the duplicate answer — a 0, never a transaction-aborting
     * 23505. The loser of a concurrent same-(recipient, source event)
     * insert learns the row exists from the count itself; the
     * pre-check/flush/catch dance is retired (the conflict is absorbed
     * inside the database, exactly the discipline the delivery ledger
     * already rides).
     *
     * <p><b>Every NOT NULL column is BOUND, none defaulted</b> (the #545
     * measured defect, closed completely): {@code created_at} and
     * {@code updated_at} are NOT NULL with NO database default on both
     * schema sources — on the JPA write path the
     * {@code AuditingEntityListener} stamps them (@CreatedDate/
     * @LastModifiedDate, the official Spring Data JPA auditing contract),
     * but a native INSERT bypasses the persistence layer entirely, so
     * the caller carries the clock's values into the statement itself
     * (the explicit-parameter discipline the official Spring Data JPA
     * query-methods documentation prescribes for native queries).
     * {@code is_deleted} and {@code version} are bound for the same
     * reason: their NOT NULL shape comes from the entity's own mapping
     * (BaseEntity's {@code @SoftDelete} and {@code @Version} columns),
     * and a database DEFAULT exists only on the Flyway chain (V25) — a
     * Hibernate-generated schema ({@code ddl-auto}, the
     * @ApplicationModuleTest slices) creates them NOT NULL with NO
     * default, so relying on V25's default answered 23502 on
     * {@code is_deleted} (the measured CI failure this closure rides).
     * Binding the constants makes the statement honest on every schema
     * the table can exist on. {@code is_read} rides the same discipline
     * (bound {@code false}); {@code created_by}/{@code updated_by} are
     * nullable and stay unbound.</p>
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO notifications (id, recipient_id, type, message, is_read,
                                       is_deleted, version, source_event_id,
                                       created_at, updated_at)
            VALUES (:id, :recipientId, :type, :message, false,
                    false, 0, :sourceEventId, :createdAt, :updatedAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertOnce(@Param("id") UUID id,
                   @Param("recipientId") UUID recipientId,
                   @Param("type") String type,
                   @Param("message") String message,
                   @Param("sourceEventId") UUID sourceEventId,
                   @Param("createdAt") Instant createdAt,
                   @Param("updatedAt") Instant updatedAt);
}
