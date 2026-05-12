package com.marketplace.payments.spi;

import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class PaymentIntentLookupAdapter implements PaymentIntentLookupPort {

    private final PaymentIntentRepository paymentIntentRepository;

    public PaymentIntentLookupAdapter(PaymentIntentRepository paymentIntentRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
    }

    @Override
    public Optional<PaymentIntentDetails> findById(UUID paymentIntentId) {
        return paymentIntentRepository.findById(paymentIntentId)
                .map(i -> new PaymentIntentDetails(i.getId(), i.getBookingId(), i.getConsumerId(), i.getStatus().name()));
    }
}
