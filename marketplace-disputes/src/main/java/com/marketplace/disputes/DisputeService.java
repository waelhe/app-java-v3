package com.marketplace.disputes;

import com.marketplace.shared.api.DisputeResolvedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class DisputeService {
    private final DisputeRepository disputeRepository;
    private final DisputeMessageRepository messageRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public DisputeService(DisputeRepository disputeRepository, DisputeMessageRepository messageRepository,
                          CurrentUserProvider currentUserProvider, ApplicationEventPublisher eventPublisher) {
        this.disputeRepository = disputeRepository; this.messageRepository = messageRepository;
        this.currentUserProvider = currentUserProvider; this.eventPublisher = eventPublisher;
    }

    public Dispute create(UUID bookingId, String reason, Authentication authentication) {
        return disputeRepository.save(Dispute.create(bookingId, currentUserProvider.getCurrentUserId(authentication), reason));
    }

    @Transactional(readOnly = true)
    public Dispute get(UUID id) { return disputeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispute", id)); }

    @Transactional(readOnly = true)
    public Page<Dispute> listMine(Pageable pageable, Authentication authentication) {
        if (currentUserProvider.isAdmin(authentication)) { return disputeRepository.findAll(pageable); }
        return disputeRepository.findByOpenedBy(currentUserProvider.getCurrentUserId(authentication), pageable);
    }

    public DisputeMessage addMessage(UUID disputeId, String message, Authentication authentication) {
        get(disputeId);
        return messageRepository.save(DisputeMessage.create(disputeId, currentUserProvider.getCurrentUserId(authentication), message));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Dispute resolve(UUID id, String resolution, boolean refundRecommended) {
        Dispute dispute = get(id);
        dispute.resolve(resolution);
        eventPublisher.publishEvent(new DisputeResolvedEvent(dispute.getId(), dispute.getBookingId(), refundRecommended));
        return dispute;
    }
}
