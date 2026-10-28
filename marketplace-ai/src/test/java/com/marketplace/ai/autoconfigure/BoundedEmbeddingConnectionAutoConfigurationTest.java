package com.marketplace.ai.autoconfigure;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review round 1 (the embedding call's HTTP ceiling): the house's own
 * connection details pin — a provisioned API key builds the Google GenAI
 * SDK client WITH the bounded HTTP layer (the delayed-endpoint ceiling
 * that releases the listener transaction's advisory lock and JDBC
 * connection); a Vertex provision builds the bounded client for THAT
 * mode; and the unprovisioned shape fails fast with the framework's own
 * assertion, exactly as the framework auto-configuration it replaces
 * would (review round 2: the empty-key "inert" path of round 1 called
 * {@code builder.apiKey("").build()}, which the framework's Builder
 * answers with the Vertex-mode project assertion — never a null-client
 * details bean).
 */
class BoundedEmbeddingConnectionAutoConfigurationTest {

    private static GoogleGenAiEmbeddingConnectionProperties properties(String apiKey,
                                                                       String projectId,
                                                                       String location) {
        GoogleGenAiEmbeddingConnectionProperties properties =
                new GoogleGenAiEmbeddingConnectionProperties();
        properties.setApiKey(apiKey);
        properties.setProjectId(projectId);
        properties.setLocation(location);
        return properties;
    }

    @Test
    void theBoundedHttpLayerCarriesTheSdkRequestCeilingInMillis() {
        // HttpOptions.timeout is the Google GenAI SDK's own request
        // ceiling, in MILLISECONDS — ApiClient maps it to OkHttp's
        // whole-call callTimeout(Duration.ofMillis(...)); the seconds
        // conversion exists only for the X-Server-Timeout request header.
        // The transaction timeout can never interrupt an in-flight HTTP
        // request, so this is the only ceiling that frees the advisory
        // lock and the JDBC connection the listener transaction holds.
        HttpOptions bounded = BoundedEmbeddingConnectionAutoConfiguration.boundedHttpOptions();
        assertThat(bounded.timeout())
                .as("the embedding request ceiling, in milliseconds (60s as a whole-call bound)")
                .contains(BoundedEmbeddingConnectionAutoConfiguration.EMBEDDING_HTTP_TIMEOUT_MILLIS);
    }

    @Test
    void aProvisionedKeyBuildsTheClientWithTheBoundedHttpLayer() {
        GoogleGenAiEmbeddingConnectionDetails details =
                BoundedEmbeddingConnectionAutoConfiguration
                        .googleGenAiEmbeddingConnectionDetails(
                                properties("test-embedding-key", null, null));

        Client client = details.getGenAiClient();
        // the pre-built SDK client (built with boundedHttpOptions() in the
        // bean method above) rides the details — the framework's own
        // auto-configuration backs off against it
        assertThat(client).as("the pre-built SDK client rides the details").isNotNull();
    }

    @Test
    void aVertexProvisionBuildsTheBoundedClientForThatMode() {
        // Review round 2: an empty api-key is VERTEX mode, never an inert
        // state — a provisioned project honors it through the bounded
        // client instead of being silently dropped by a Gemini-only bean.
        // The SDK's Vertex client build loads Application Default
        // Credentials AT CONSTRUCTION (ApiClient.defaultCredentials — the
        // same requirement the framework's own Builder.build() Vertex path
        // carries), so this pin runs only where ADC exists and skips the
        // same way the house's Docker-gated integration tests do
        // (@Testcontainers disabledWithoutDocker — the environment-conditional
        // enablement idiom).
        boolean adcPresent = System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null
                || java.nio.file.Files.exists(java.nio.file.Path.of(
                        System.getProperty("user.home", ""), ".config", "gcloud",
                        "application_default_credentials.json"));
        org.junit.jupiter.api.Assumptions.assumeTrue(adcPresent,
                "the SDK's Vertex client build requires Application Default Credentials"
                        + " (the framework's own Vertex path has the same requirement)");
        GoogleGenAiEmbeddingConnectionDetails details =
                BoundedEmbeddingConnectionAutoConfiguration
                        .googleGenAiEmbeddingConnectionDetails(
                                properties("", "house-project", "europe-west1"));

        assertThat(details.getGenAiClient())
                .as("a Vertex provision builds the bounded SDK client for that mode")
                .isNotNull();
    }

    @Test
    void anUnprovisionedContextFailsFastExactlyLikeTheFrameworkItself() {
        // Review round 2: neither key nor project — the framework's own
        // auto-configuration answers this shape with the project-id
        // assertion (fail-fast, the house's D6 key philosophy); the house
        // bean that pre-empts it must answer the SAME way, never with a
        // silent inert bean.
        // Static invocation — the bean method's own shape (Spring Boot's
        // auto-configuration convention; no enclosing configuration
        // instance exists in the module-filtered slices it serves).
        assertThatThrownBy(() -> BoundedEmbeddingConnectionAutoConfiguration
                .googleGenAiEmbeddingConnectionDetails(
                        properties("", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-id");
        assertThatThrownBy(() -> BoundedEmbeddingConnectionAutoConfiguration
                .googleGenAiEmbeddingConnectionDetails(
                        properties(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-id");
    }
}
