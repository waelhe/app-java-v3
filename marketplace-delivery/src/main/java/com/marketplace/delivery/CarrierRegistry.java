package com.marketplace.delivery;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The carrier registry (§3.4's «carrier/partner adapters, active
 * serviceability/capabilities»): the configured {@link CarrierChannel}s
 * by their stable codes — the platform fleet always (the launch mode),
 * external partners behind their own adapters as their approvals arrive.
 * The registry is the ONE place the core asks «who can carry this?» —
 * provider selection stays a measured operational decision, never
 * hardcoded provider logic inside the domain.
 */
@Component
public class CarrierRegistry {

    private final Map<String, CarrierChannel> channels;

    public CarrierRegistry(Collection<CarrierChannel> channels) {
        Map<String, CarrierChannel> byCode = new LinkedHashMap<>();
        for (CarrierChannel channel : channels) {
            // CodeRabbit round 1, verified real: a duplicate code must be a
            // LOUD configuration error at startup — a silent put would
            // replace the earlier channel, hide it from all(), and answer
            // only the later one from byCode() (§3.4's stable-code contract).
            CarrierChannel previous = byCode.putIfAbsent(channel.code(), channel);
            if (previous != null) {
                throw new IllegalStateException("Duplicate carrier code '" + channel.code()
                        + "': " + previous.getClass().getName() + " and "
                        + channel.getClass().getName()
                        + " declare the same code — carrier codes must be unique");
            }
        }
        this.channels = java.util.Collections.unmodifiableMap(byCode);
    }

    /** Every configured carrier, in registration order. */
    public Collection<CarrierChannel> all() {
        return channels.values();
    }

    public Optional<CarrierChannel> byCode(String code) {
        return Optional.ofNullable(channels.get(code));
    }
}
