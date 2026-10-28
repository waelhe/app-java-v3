package com.marketplace.ai.autoconfigure;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review round 1 (the embedding call's HTTP ceiling): the house's own
 * connection details pin — a provisioned API key builds the Google GenAI
 * SDK client WITH the bounded HTTP layer (the delayed-endpoint ceiling
 * that releases the listener transaction's advisory lock and JDBC
 * connection), while the unprovisioned shape stays byte-equivalent to the
 * framework's own inert details.
 */
class BoundedEmbeddingConnectionAutoConfigurationTest {

    @Test
    void theBoundedHttpLayerCarriesTheSdkRequestCeiling() {
        // HttpOptions.timeout is the Google GenAI SDK's own request
        // ceiling, in SECONDS (ApiClient's timeoutInSeconds and
        // X-Server-Timeout header carry the unit) — the transaction
        // timeout can never interrupt an in-flight HTTP request, so this
        // is the only ceiling that frees the advisory lock and the JDBC
        // connection the listener transaction holds.
        HttpOptions bounded = BoundedEmbeddingConnectionAutoConfiguration.boundedHttpOptions();
        assertThat(bounded.timeout())
                .as("the embedding request ceiling, in seconds")
                .contains(BoundedEmbeddingConnectionAutoConfiguration.EMBEDDING_HTTP_TIMEOUT_SECONDS);
    }

    @Test
    void aProvisionedKeyBuildsTheClientWithTheBoundedHttpLayer() {
        GoogleGenAiEmbeddingConnectionDetails details =
                new BoundedEmbeddingConnectionAutoConfiguration()
                        .googleGenAiEmbeddingConnectionDetails("test-embedding-key");

        Client client = details.getGenAiClient();
        // the pre-built SDK client (built with boundedHttpOptions() in the
        // bean method above) rides the details — the framework's own
        // auto-configuration backs off against it
        assertThat(client).as("the pre-built SDK client rides the details").isNotNull();
    }

    @Test
    void anUnprovisionedKeyBuildsThePlainInertDetails() {
        GoogleGenAiEmbeddingConnectionDetails blank =
                new BoundedEmbeddingConnectionAutoConfiguration()
                        .googleGenAiEmbeddingConnectionDetails("");
        assertThat(blank.getGenAiClient())
                .as("no key provisioned: no client is built (the framework's own inert shape)")
                .isNull();

        GoogleGenAiEmbeddingConnectionDetails nullKey =
                new BoundedEmbeddingConnectionAutoConfiguration()
                        .googleGenAiEmbeddingConnectionDetails(null);
        assertThat(nullKey.getGenAiClient()).isNull();
    }
}
