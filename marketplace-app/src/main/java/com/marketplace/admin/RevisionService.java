package com.marketplace.admin;

import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class RevisionService {

    private static final Map<String, String> ENTITY_CLASSES = new LinkedHashMap<>();

    static {
        ENTITY_CLASSES.put("User", "com.marketplace.identity.User");
        ENTITY_CLASSES.put("ProviderListing", "com.marketplace.catalog.ProviderListing");
        ENTITY_CLASSES.put("Booking", "com.marketplace.booking.Booking");
        ENTITY_CLASSES.put("Payment", "com.marketplace.payments.Payment");
        ENTITY_CLASSES.put("PaymentIntent", "com.marketplace.payments.PaymentIntent");
        ENTITY_CLASSES.put("PaymentWebhookEvent", "com.marketplace.payments.PaymentWebhookEvent");
        ENTITY_CLASSES.put("PricingRule", "com.marketplace.pricing.PricingRule");
        ENTITY_CLASSES.put("Review", "com.marketplace.reviews.Review");
        ENTITY_CLASSES.put("Message", "com.marketplace.messaging.Message");
        ENTITY_CLASSES.put("Conversation", "com.marketplace.messaging.Conversation");
        ENTITY_CLASSES.put("ProviderProfile", "com.marketplace.provider.ProviderProfile");
        ENTITY_CLASSES.put("AvailabilitySlot", "com.marketplace.availability.AvailabilitySlot");
        ENTITY_CLASSES.put("ProviderAvailabilityRule", "com.marketplace.availability.ProviderAvailabilityRule");
        ENTITY_CLASSES.put("ProviderTimeOff", "com.marketplace.availability.ProviderTimeOff");
        ENTITY_CLASSES.put("Notification", "com.marketplace.notifications.Notification");
        ENTITY_CLASSES.put("LedgerEntry", "com.marketplace.ledger.LedgerEntry");
        ENTITY_CLASSES.put("ProviderBalance", "com.marketplace.ledger.ProviderBalance");
        ENTITY_CLASSES.put("Dispute", "com.marketplace.disputes.Dispute");
    }

    private final EntityManager entityManager;

    public RevisionService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public static Set<String> getEntityNames() {
        return ENTITY_CLASSES.keySet();
    }

    public Class<?> resolveEntityClass(String entityName) {
        String className = ENTITY_CLASSES.get(entityName);
        if (className == null) {
            throw new IllegalArgumentException("Unknown entity: " + entityName);
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Entity class not found: " + className, e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<RevisionEntry> getRevisions(String entityName, UUID entityId) {
        Class<?> entityClass = resolveEntityClass(entityName);
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        List<Object[]> results = auditReader.createQuery()
                .forRevisionsOfEntity(entityClass, false, true)
                .add(AuditEntity.id().eq(entityId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        List<RevisionEntry> entries = new ArrayList<>(results.size());

        for (Object[] row : results) {
            Object entity = row[0];
            Object revisionInfo = row[1];
            RevisionType revisionType = (RevisionType) row[2];
            int revisionNumber = 0;
            Instant revisedAt = Instant.now();

            if (revisionInfo instanceof org.hibernate.envers.DefaultRevisionEntity dre) {
                revisionNumber = dre.getId();
                revisedAt = dre.getRevisionDate().toInstant();
            }

            entries.add(new RevisionEntry(
                    revisionNumber,
                    revisedAt,
                    revisionType.name(),
                    entity
            ));
        }

        return entries;
    }

    public record RevisionEntry(
            int revisionNumber,
            Instant revisedAt,
            String revisionType,
            Object entity
    ) {}
}
