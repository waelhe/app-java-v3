package com.marketplace.ai.autoconfigure;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Review round 1 (the embedding call's HTTP ceiling): the house's own
 * {@link GoogleGenAiEmbeddingConnectionDetails} — the Google GenAI SDK
 * client built with a bounded HTTP layer.
 *
 * <p><b>The measured window:</b> {@code vectorStore.add} (the embedding
 * request) runs INSIDE the Modulith listener's own REQUIRES_NEW
 * transaction — the advisory lock and the JDBC connection are held for
 * the whole call. The SDK's default HTTP layer is UNBOUNDED by design
 * (google-genai 1.65.0 {@code ApiClient} removes OkHttp's own timeouts:
 * {@code connectTimeout(0)}, {@code readTimeout(0)},
 * {@code writeTimeout(0)}), so a delayed embedding endpoint would hold
 * the lock and the connection for the entire delay, and the transaction
 * timeout cannot help: Spring applies a participating definition's
 * timeout only to transactions it starts, and never interrupts an
 * in-flight HTTP request anyway. {@link HttpOptions#timeout(Integer)} is
 * the request ceiling — in MILLISECONDS ({@code ApiClient} maps it to
 * OkHttp's whole-call {@code callTimeout(Duration.ofMillis(...))}; the
 * seconds conversion exists only for the {@code X-Server-Timeout} request
 * header, not for the timeout unit) — the delayed request fails at the
 * ceiling and the lock and connection are released.</p>
 *
 * <p><b>The precedence contract:</b> this configuration is processed
 * BEFORE Spring AI's {@code GoogleGenAiEmbeddingConnectionAutoConfiguration}
 * (the {@code beforeName} below), so the house bean registers first and
 * the framework's {@code @ConditionalOnMissingBean} backs its own details
 * bean off — the documented auto-configuration ordering rules for bean
 * conditions. The branching mirrors the framework's own configuration
 * verbatim (review round 2: the Gemini/Vertex modes and the properties
 * binding — {@code api-key}, {@code project-id}, {@code location} — are
 * the framework's; an empty api-key is Vertex mode, and the unprovisioned
 * shape with neither key nor project fails fast with the framework's own
 * assertion, never a silent inert bean that pre-empts it).</p>
 */
@AutoConfiguration(
        beforeName = "org.springframework.ai.model.google.genai.autoconfigure.embedding."
                + "GoogleGenAiEmbeddingConnectionAutoConfiguration")
@ConditionalOnClass({Client.class, HttpOptions.class, GoogleGenAiEmbeddingConnectionDetails.class,
        GoogleGenAiEmbeddingConnectionProperties.class})
public class BoundedEmbeddingConnectionAutoConfiguration {

    /**
     * The embedding request's HTTP ceiling, in MILLISECONDS — 60 seconds
     * as a whole-call bound (connection, write and read together, OkHttp
     * {@code callTimeout}): generous for the token-split batches the
     * gateway submits (a normal batch answers in single-digit seconds)
     * while bounding the delayed-endpoint window the advisory lock and
     * JDBC connection would otherwise absorb. Review round 2: the SDK
     * multiplies nothing here — {@code HttpOptions.timeout(60)} would be
     * SIXTY MILLISECONDS, killing every real request at the ceiling.
     */
    static final int EMBEDDING_HTTP_TIMEOUT_MILLIS = 60_000;

    /*
     * STATIC — the Spring Boot auto-configuration convention (the
     * framework's own contributing guide: make @Bean methods static
     * where possible, so the bean never needs the enclosing
     * configuration instance). Measured on the app shards (2026-10-28,
     * carry-3 head 0f28c90c): Spring Modulith's @ApplicationModuleTest
     * keeps this bean definition (its type is a framework class, outside
     * the project packages the module filter removes) while dropping the
     * configuration-class bean — a NON-static factory method then dies at
     * "No bean named '...BoundedEmbeddingConnectionAutoConfiguration'
     * available" in every non-AI module slice (payments, media,
     * availability, provider, identity, ledger, reviews, search — the
     * vector-store chain still needs the details there). Static removes
     * the enclosing-instance dependency: the method is invoked without
     * the configuration bean, exactly what the convention exists for.
     */
    @Bean
    @ConditionalOnMissingBean(GoogleGenAiEmbeddingConnectionDetails.class)
    static GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
            GoogleGenAiEmbeddingConnectionProperties properties) {
        Client.Builder clientBuilder = Client.builder()
                .httpOptions(boundedHttpOptions());
        if (StringUtils.hasText(properties.getApiKey())) {
            // Gemini Developer API mode — the repository's own shape
            // (application.yml pins the embedding api-key secret).
            clientBuilder.apiKey(properties.getApiKey());
        }
        else {
            // Vertex AI mode — the framework autoconfiguration's own
            // assertions and defaults, mirrored verbatim: an empty api-key
            // is NOT an inert state, it is Vertex mode, and the
            // unprovisioned shape (neither key nor project) fails fast
            // with the framework's own message.
            Assert.hasText(properties.getProjectId(), "Google GenAI project-id must be set!");
            Assert.hasText(properties.getLocation(), "Google GenAI location must be set!");
            clientBuilder.project(properties.getProjectId())
                    .location(properties.getLocation())
                    .vertexAI(true);
        }
        return GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(properties.getApiKey())
                .genAiClient(clientBuilder.build())
                .build();
    }

    /**
     * The bounded HTTP layer itself — package-visible for the pin test:
     * the SDK's own whole-call request ceiling, in MILLISECONDS (ApiClient
     * maps it to OkHttp {@code callTimeout(Duration.ofMillis(...))}; the
     * {@code X-Server-Timeout} header's seconds conversion is a header
     * concern, not the unit of this setting).
     */
    static HttpOptions boundedHttpOptions() {
        return HttpOptions.builder()
                .timeout(EMBEDDING_HTTP_TIMEOUT_MILLIS)
                .build();
    }
}
