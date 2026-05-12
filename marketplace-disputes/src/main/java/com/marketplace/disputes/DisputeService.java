package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DisputeService {
    private final DisputeRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final BookingParticipantProvider bookingParticipantProvider;

    public DisputeService(DisputeRepository repository, CurrentUserProvider currentUserProvider, BookingParticipantProvider bookingParticipantProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
    }

    public Dispute open(UUID bookingId, String reason, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        info.requireParticipant(userId);
        return repository.save(Dispute.open(bookingId, userId, reason));
    }

    @Transactional(readOnly = true)
    public List<Dispute> listForBooking(UUID bookingId, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        if (!currentUserProvider.isAdmin(authentication)) {
            info.requireParticipant(userId);
        }
        return repository.findByBookingId(bookingId);
    }

    public Dispute resolve(UUID id, Authentication authentication) {
        if (!currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("Only admins can resolve disputes");
        }
        Dispute dispute = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispute not found: " + id));
        dispute.resolve();
        return dispute;
    }
}
