package com.marketplace.ai.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider model IDs used by the optional TypeSafe model router.
 *
 * <p>Spring Boot binds this grouped configuration automatically. The selected
 * Spring AI provider is still selected by Spring AI's own model selector.</p>
 */
@ConfigurationProperties(prefix = "marketplace.ai.typesafe.model-routing")
public class TypeSafeModelRoutingProperties {

    private ProviderModels google = new ProviderModels("gemini-3.5-flash-lite", "gemini-3.8-flash");
    private ProviderModels deepseek = new ProviderModels("deepseek-flash", "deepseek-v4-pro");

    public ProviderModels getGoogle() {
        return google;
    }

    public void setGoogle(ProviderModels google) {
        this.google = google;
    }

    public ProviderModels getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(ProviderModels deepseek) {
        this.deepseek = deepseek;
    }

    public static class ProviderModels {

        private String fastModel;
        private String capableModel;

        public ProviderModels() {
        }

        private ProviderModels(String fastModel, String capableModel) {
            this.fastModel = fastModel;
            this.capableModel = capableModel;
        }

        public String getFastModel() {
            return fastModel;
        }

        public void setFastModel(String fastModel) {
            this.fastModel = fastModel;
        }

        public String getCapableModel() {
            return capableModel;
        }

        public void setCapableModel(String capableModel) {
            this.capableModel = capableModel;
        }
    }
}
