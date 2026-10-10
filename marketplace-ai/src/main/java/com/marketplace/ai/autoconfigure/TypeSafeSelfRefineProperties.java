package com.marketplace.ai.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot properties for the optional TypeSafe response-judgment loop.
 *
 * <p>The response rubric itself stays in the official JevJudge API, while retry
 * bounds and exhaustion behavior use normal Spring Boot property binding.</p>
 */
@ConfigurationProperties(prefix = "marketplace.ai.typesafe.self-refine")
public class TypeSafeSelfRefineProperties {

    private int maxRepeatAttempts = 2;
    private boolean failOnExhaustedAttempts;

    public int getMaxRepeatAttempts() {
        return maxRepeatAttempts;
    }

    public void setMaxRepeatAttempts(int maxRepeatAttempts) {
        this.maxRepeatAttempts = maxRepeatAttempts;
    }

    public boolean isFailOnExhaustedAttempts() {
        return failOnExhaustedAttempts;
    }

    public void setFailOnExhaustedAttempts(boolean failOnExhaustedAttempts) {
        this.failOnExhaustedAttempts = failOnExhaustedAttempts;
    }
}
