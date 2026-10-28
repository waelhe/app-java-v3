package com.marketplace.ai.autoconfigure;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Review round 1 (the embedding call's HTTP ceiling): the house's own
 * {@link GoogleGenAiEmbeddingConnectionDetails} — the Google GenAI SDK
 * client built with a bounded HTTP layer.
 *
 * <p><b>The measured window:</b> {@code vectorStore.add} (the embedding
 * request) runs INSIDE the Modulith listener's own REQUIRES_NEW
 * transaction — the advisory lock and the JDBC connection are held for
 * the whole call. The SDK's default HTTP layer is unbounded, so a delayed
 * embedding endpoint holds the lock and the connection for the entire
 * delay, and the transaction timeout cannot help: Spring checks timeouts
 * at JDBC statements and never interrupts an in-flight HTTP request. The
 * SDK's own {@link HttpOptions#timeout} (seconds — the client's
 * {@code timeoutInSeconds} and {@code X-Server-Timeout} header are the
 * unit's evidence) is the request ceiling the finding asks for: the
 * delayed request fails at the ceiling and the lock and connection are
 * released.</p>
 *
 * <p><b>The precedence contract:</b> this configuration is processed
 * BEFORE Spring AI's {@code GoogleGenAiEmbeddingConnectionAutoConfiguration}
 * (the {@code beforeName} below), so the house bean registers first and
 * the framework's {@code @ConditionalOnMissingBean} backs its own details
 * bean off — the documented auto-configuration ordering rules for bean
 * conditions. The unprovisioned shape (a blank API key) builds the same
 * plain details the framework's configuration would, preserving every
 * inert path.</p>
 */
@AutoConfiguration(
        beforeName = "org.springframework.ai.model.google.genai.autoconfigure.embedding."
                + "GoogleGenAiEmbeddingConnectionAutoConfiguration")
@ConditionalOnClass(GoogleGenAiEmbeddingConnectionDetails.class)
public class BoundedEmbeddingConnectionAutoConfiguration {

    /**
     * The embedding request's HTTP ceiling, in seconds — generous for the
     * token-split batches the gateway submits (a normal batch answers in
     * single-digit seconds) while bounding the delayed-endpoint window
     * the advisory lock and JDBC connection would otherwise absorb.
     */
    static final int EMBEDDING_HTTP_TIMEOUT_SECONDS = 60;

    @Bean
    @ConditionalOnMissingBean(GoogleGenAiEmbeddingConnectionDetails.class)
    GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
            @Value("${spring.ai.google.genai.embedding.api-key:}") String apiKey) {
        GoogleGenAiEmbeddingConnectionDetails.Builder builder =
                GoogleGenAiEmbeddingConnectionDetails.builder();
        if (apiKey == null || apiKey.isBlank()) {
            // The unprovisioned shape: exactly what the framework's own
            // configuration would build — the plain details, no client.
            return builder.apiKey(apiKey).build();
        }
        Client boundedClient = Client.builder()
                .apiKey(apiKey)
                .httpOptions(boundedHttpOptions())
                .build();
        return builder
                .apiKey(apiKey)
                .genAiClient(boundedClient)
                .build();
    }

    /**
     * The bounded HTTP layer itself — package-visible for the pin test: the
     * SDK's own request ceiling, in seconds (ApiClient's timeoutInSeconds
     * and X-Server-Timeout header carry the unit).
     */
    static HttpOptions boundedHttpOptions() {
        return HttpOptions.builder()
                .timeout(EMBEDDING_HTTP_TIMEOUT_SECONDS)
                .build();
    }
}
