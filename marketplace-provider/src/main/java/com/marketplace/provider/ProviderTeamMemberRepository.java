package com.marketplace.provider;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * W5b (the journeys register's J29 row): the delegation registry's
 * repository — the module's own house shape (JpaRepository +
 * JpaSpecificationExecutor + Envers RevisionRepository, the
 * {@code BusinessHourRepository} form).
 */
public interface ProviderTeamMemberRepository extends JpaRepository<ProviderTeamMember, UUID>,
        JpaSpecificationExecutor<ProviderTeamMember>,
        RevisionRepository<ProviderTeamMember, UUID, Integer> {

    /**
     * The owner's team read: one provider's live roster in arrival order —
     * the deterministic total order ({@code createdAt ASC, id ASC}, the
     * verification-queue discipline). Soft-deleted rows are structurally
     * filtered by {@code @SoftDelete}; REVOKED seats are excluded by the
     * status parameter (the history stays in the Envers trail).
     */
    List<ProviderTeamMember> findByProviderIdAndStatusOrderByCreatedAtAscIdAsc(
            UUID providerId, ProviderTeamMemberStatus status);

    /**
     * The membership seam: the live row for one provider-member pair — the
     * unique key's read form ({@code uq_provider_team_one_live}).
     */
    Optional<ProviderTeamMember> findByProviderIdAndMemberUserIdAndStatus(
            UUID providerId, UUID memberUserId, ProviderTeamMemberStatus status);

    /**
     * The duplicate gate's own probe: whether the member already holds a
     * LIVE seat on this provider — the read the add flow checks BEFORE any
     * write (the index stays the concurrent writer's backstop).
     */
    boolean existsByProviderIdAndMemberUserIdAndStatus(
            UUID providerId, UUID memberUserId, ProviderTeamMemberStatus status);
}
