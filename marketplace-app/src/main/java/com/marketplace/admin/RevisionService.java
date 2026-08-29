package com.marketplace.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RevisionService {

    private final Map<String, Class<?>> entityClasses;
    private final EntityManager entityManager;

    public RevisionService(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.entityClasses = entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .filter(Objects::nonNull)
                .filter(cls -> cls.isAnnotationPresent(org.hibernate.envers.Audited.class))
                .collect(Collectors.toMap(Class::getSimpleName, cls -> cls));
    }

    public Set<String> getEntityNames() {
        return entityClasses.keySet();
    }

    public Class<?> resolveEntityClass(String entityName) {
        Class<?> clazz = entityClasses.get(entityName);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown entity: " + entityName);
        }
        return clazz;
    }

    public List<RevisionEntry> getRevisions(String entityName, UUID entityId) {
        Class<?> entityClass = resolveEntityClass(entityName);
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        List<Object[]> results = queryRevisions(auditReader, entityClass, entityId);

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

    @SuppressWarnings("unchecked")
    private List<Object[]> queryRevisions(AuditReader auditReader, Class<?> entityClass, UUID entityId) {
        return auditReader.createQuery()
                .forRevisionsOfEntity(entityClass, false, true)
                .add(AuditEntity.id().eq(entityId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();
    }

    public record RevisionEntry(
            int revisionNumber,
            Instant revisedAt,
            String revisionType,
            Object entity
    ) {}
}
