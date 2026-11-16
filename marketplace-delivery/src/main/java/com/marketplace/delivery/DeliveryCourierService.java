package com.marketplace.delivery;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
@Service @Transactional
public class DeliveryCourierService {
    private final DeliveryProperties properties;
    private final DeliveryCourierRepository couriers;
    private final ProviderLookupPort providers;
    public DeliveryCourierService(DeliveryProperties p, DeliveryCourierRepository c, ProviderLookupPort providers) {
        this.properties=p; this.couriers=c; this.providers=providers;
    }
    @Observed(name="delivery.courier.availability")
    public DeliveryCourierResponse updateAvailability(UUID userId, CourierAvailabilityRequest input) {
        if (!properties.enabled()) throw new ServiceUnavailableException("Direct delivery is disabled");
        var provider=providers.findByUserId(userId)
                .filter(p -> "VERIFIED".equals(p.status()))
                .orElseThrow(() -> new ResourceNotFoundException("A verified provider profile is required to provide delivery"));
        var courier=couriers.findByUserIdForUpdate(userId).orElseGet(() -> new DeliveryCourier(provider.userId()));
        courier.updateAvailability(input.latitude(), input.longitude(), input.available(), Instant.now());
        return DeliveryCourierResponse.from(couriers.save(courier));
    }
}