# Marketplace AI module

This module keeps text generation, structured interpretation, retrieval and model judgment on Spring AI APIs. TypeSafe/Jev supplements the configured chat provider; it is **not itself a Spring AI chat model**.

## Baseline behavior

Without the `typesafe` Spring profile, the application uses the active Spring AI provider selected by `spring.ai.model.chat`. The standard Spring AI `QuestionAnswerAdvisor` is used for vector retrieval when a `VectorStore` is available. The separate TypeSafe client and the TypeSafe RAG advisor are not activated.

## Enable the optional TypeSafe integrations

Activate the `typesafe` profile alongside the normal environment profile, and provision the secret `TYPESAFE_API_KEY` in the environment. Never commit the key to this repository.

The profile enables two independent capabilities:

- **Jev model routing**: one TypeSafe `Choice` call classifies a request as `FAST` or `CAPABLE`. The selected model ID is passed through the active provider's Spring AI options builder. If Jev fails, returns an unknown choice, or confidence is below `marketplace.ai.typesafe.model-routing.minimum-confidence` (default `0.65`), the router falls back to the configured `CAPABLE` model.
- **Jev-enhanced RAG**: Spring AI's official `RetrievalAugmentationAdvisor` retrieves up to five public vector-store passages, then TypeSafe's official community `JevDocumentFilter` screens them and `JevDocumentReranker` reorders surviving passages before the prompt is augmented. The context formatter separates supporting passages, contradictions and passages that could not be screened.

Model routing does not switch provider or credentials. Spring AI's existing `spring.ai.model.chat` selector continues to choose the active provider. The profile's defaults are:

| Active provider | FAST | CAPABLE |
|---|---|---|
| Google GenAI | `gemini-3.5-flash-lite` | `gemini-3.5-flash` |
| DeepSeek | `deepseek-flash` | `deepseek-v4-pro` |

Override these using `SPRING_AI_ROUTING_GOOGLE_FAST_MODEL`, `SPRING_AI_ROUTING_GOOGLE_CAPABLE_MODEL`, `SPRING_AI_ROUTING_DEEPSEEK_FAST_MODEL`, or `SPRING_AI_ROUTING_DEEPSEEK_CAPABLE_MODEL`. Override the confidence floor with `TYPESAFE_ROUTING_MIN_CONFIDENCE`.

## Runtime requirements and trade-offs

- Keep the usual provider key for the active chat provider. Google GenAI embeddings and the pgvector store have their own existing configuration requirements.
- The TypeSafe profile adds a Jev routing call per chat request. Screening and reranking make additional Jev calls based on how many passages are retrieved/survive the filter; the feature is opt-in rather than silently imposed on every deployment.
- The current routing is intentionally within-provider. It does not dynamically switch Google GenAI to DeepSeek (or vice versa), because the official Spring AI provider auto-configuration selects one chat model at startup.
- **DeepSeek model caveat (verified against official documentation on 2026-10-09):** use `deepseek-flash`, not the retired `deepseek-v4-flash` alias. DeepSeek currently routes `deepseek-v4-pro` requests to V4.1-Flash until V4.1-Pro is launched, so FAST/CAPABLE may resolve to the same provider-side model during that transition. The router selects configured model IDs; it cannot override server-side routing. See [DeepSeek's V4.1-Flash announcement](https://www.deepseek.com/en/news/deepseek-v4-1-flash/) and the [official model-list API](https://api-docs.deepseek.com/api/list-models/).
- **JevGuardrailAdvisor** is enabled by the `typesafe` profile and screens both incoming user text and the generated answer. Upstream `JevGuardrailAdvisor.adviseStream()` explicitly throws `UnsupportedOperationException`, so the gateway routes SSE-shaped requests through the official guarded `call()` path and emits the verified answer only after the advisor finishes. SSE remains available, but incremental token-by-token delivery is disabled while guardrails are active; without guardrails the official Spring AI streaming path remains incremental. This avoids an unguarded streaming bypass.
- **JevSelfRefineAdvisor / JevJudge** are not enabled globally yet: the official API requires domain-specific evaluation criteria and retry policy, and every retry incurs another model call plus a Jev judgment call. No marketplace-specific pass/fail rubric has been defined, so enabling a generic judge by default would be an arbitrary policy rather than a faithful integration.
- TypeSafe's official document filter passes a passage through as unclassified if Jev cannot screen it. Such passages are labelled `UNSCREENED` in the augmented context rather than represented as verified evidence; this is fail-open behavior, not a guarantee that screening succeeded.

## Upstream references

- [Spring AI TypeSafe structured judgment announcement](https://spring.io/blog/2026/09/21/spring-ai-typesafe-structured-judgment)
- [Spring AI Community TypeSafe repository and reference docs](https://github.com/spring-ai-community/spring-ai-typesafe)
- [Upstream RAG pipeline example](https://github.com/spring-ai-community/spring-ai-typesafe/blob/v0.4.0/examples/src/main/java/org/springaicommunity/typesafe/demo/rag/RagPipelineDemo.java)
- [Model-router implementation example](https://github.com/danvega/spring-ai-model-router)
- [Spring AI 2.0.1 reference](https://docs.spring.io/spring-ai/reference/)
