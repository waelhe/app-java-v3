package com.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@Modulithic
@EnableConfigurationProperties(MarketplaceProperties.class)
public class MarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
