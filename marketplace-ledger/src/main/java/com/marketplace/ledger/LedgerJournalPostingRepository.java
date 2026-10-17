package com.marketplace.ledger;
import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;import java.util.UUID;
public interface LedgerJournalPostingRepository extends JpaRepository<LedgerJournalPosting,UUID>{
 Optional<LedgerJournalPosting> findBySourceTypeAndSourceIdAndOperationKey(String sourceType,UUID sourceId,String operationKey);
 Optional<LedgerJournalPosting> findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc(String sourceType,UUID sourceId);
}
