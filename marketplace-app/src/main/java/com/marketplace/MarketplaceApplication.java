package com.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The shard review (Trivial · Performance & Scalability, 2026-11-01 wave
 * 2) activated the platform's first {@code @Scheduled} job — the
 * consumed-refresh-token ledger's bounded-lifetime cleanup
 * (ConsumedRefreshTokenCleanupJob). Spring Boot's own model
 * (docs.spring.io/spring-boot — Task Execution and Scheduling):
 * {@code @EnableScheduling} on a configuration class activates every
 * {@code @Scheduled} method; without it the job silently never runs.
 */
@SpringBootApplication
@Modulithic
@EnableConfigurationProperties(MarketplaceProperties.class)
@EnableScheduling
public class MarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
