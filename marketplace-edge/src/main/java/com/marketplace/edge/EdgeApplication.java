package com.marketplace.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Edge BFF — standalone deployable serving client-hosting-strategy-plan §4
 * pattern (1) confidential web clients.
 *
 * <p>Deliberately OUTSIDE Modulith domain verification: edge holds zero domain
 * state and zero {@code @ApplicationModule} beans, so
 * {@code ModulithVerificationTest} (marketplace-app context) is unaffected —
 * proven by the module build (Task 2, Step 5 of the edge plan).
 */
@SpringBootApplication
public class EdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeApplication.class, args);
    }
}
