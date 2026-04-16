package com.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@Modulithic
@EnableAsync
@EnableScheduling
@EnableMethodSecurity
@EnableJpaRepositories("com.marketplace")
public class MarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}