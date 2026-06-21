package com.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled task execution for the JWK rotation job
 * (see {@code SecurityConfig.rotateJwk}) and any other @Scheduled beans.
 * Reference: https://docs.spring.io/spring-boot/reference/features/task-execution-scheduling.html#features.task-execution-scheduling.scheduler
 */
@SpringBootApplication
@Modulithic
@EnableConfigurationProperties({MarketplaceProperties.class, com.marketplace.shared.storage.StorageProperties.class})
@EnableScheduling
public class MarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
