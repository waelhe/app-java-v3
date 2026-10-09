# Marketplace AI module

This module uses Spring AI's managed `ChatClient.Builder`, provider auto-configuration, Tool Calling, structured output, Session memory, RAG and vector-store APIs. TypeSafe/Jev is an optional Spring AI Community integration for decisions, answer evaluation, input/output guardrails, retrieved-document screening and dynamic tool discovery. Jev is **not** the chat-generation model.

## Baseline behavior

Without the `typesafe` Spring profile, the active chat provider is selected by Spring AI's `spring.ai.model.chat` property. If a `VectorStore` is available, the module uses Spring AI's `QuestionAnswerAdvisor` with the public-visibility filter. Spring AI's configured `ChatClient.Builder`, provider model and Tool Calling infrastructure remain in control; the module does not implement a separate tool-call loop.

## Enable the TypeSafe profile

Activate `typesafe` alongside the normal environment profile and provision `TYPESAFE_API_KEY` as an environment secret. Do not commit provider credentials. The environment must also provide the configured chat provider's credentials and the Google embedding credentials needed by the PGVector auto-configuration.

The profile enables the following integrations. The `judge.enabled` switch registers the reusable `JevJudge` and Spring AI `Evaluator`. The separate `self-refine.enabled` switch adds the chat retry advisor only when `judge.enabled` is also true.

- **Model routing (Google GenAI and DeepSeek only):** Jev's typed `Choice` classifies the request as `FAST` or `CAPABLE`. Other Spring AI providers remain selected and used normally, but this project-specific router does not create routing beans for them. The active Spring AI provider remains selected by Spring AI; the router passes a per-request model option through that provider's official options builder. It uses TypeSafe's official `JevConfidenceGate` default floor and falls back to the configured capable model when routing fails or is inconclusive. Google defaults are `gemini-3.5-flash-lite` and `gemini-3.8-flash`; DeepSeek defaults are `deepseek-flash` and `deepseek-v4-pro`. Override model IDs with `SPRING_AI_ROUTING_GOOGLE_FAST_MODEL`, `SPRING_AI_ROUTING_GOOGLE_CAPABLE_MODEL`, `SPRING_AI_ROUTING_DEEPSEEK_FAST_MODEL`, or `SPRING_AI_ROUTING_DEEPSEEK_CAPABLE_MODEL`.
- **Evaluation and self-refinement:** the official community `JevJudge` checks helpfulness and grounding against the context in the prompt and recorded tool results. The module exposes TypeSafe's official `JevEvaluator` through Spring AI's `Evaluator` SPI whenever `marketplace.ai.typesafe.judge.enabled=true`. `JevSelfRefineAdvisor` is a separate optional adapter that feeds failed criteria back to the model for bounded retries. The default maximum is two retries; tune it with `TYPESAFE_SELF_REFINE_MAX_REPEAT_ATTEMPTS`. When retries are exhausted, the official advisor's best-effort behavior is retained by default; `TYPESAFE_SELF_REFINE_FAIL_ON_EXHAUSTED_ATTEMPTS=true` makes exhaustion fail the call.
- **DeepSeek model-routing caveat (verified 2026-10-09):** the official model list exposes `deepseek-flash`, while DeepSeek's V4.1-Flash announcement states requests to `deepseek-v4-pro` are routed to V4.1-Flash until V4.1-Pro launches. In that provider-side transition window, FAST and CAPABLE can resolve to the same underlying model; the router cannot override DeepSeek's server-side alias routing. References: [official model list](https://api-docs.deepseek.com/api/list-models/) and [V4.1-Flash announcement](https://www.deepseek.com/en/news/deepseek-v4-1-flash/).
- **Guardrails:** `JevGuardrailAdvisor` checks both the incoming request and final answer. An unsafe request can be blocked before generation; the output is screened before being returned.
- **Dynamic tool discovery:** the official `spring-ai-starter-tool-search-advisor` owns Spring AI's `ToolSearchToolCallingAdvisor` construction and integration with `ChatClient.Builder`. The module contributes TypeSafe's official community `JevToolIndex` through Spring AI's `ToolIndex` SPI. When the official advisor is enabled without TypeSafe credentials, Spring AI automatically supplies its default `RegexToolIndex`.
- **RAG:** Spring AI's official `RetrievalAugmentationAdvisor` retrieves public documents using the framework's default retrieval settings. TypeSafe's official `JevDocumentFilter` screens documents before `JevDocumentReranker` ranks survivors. Supporting, contradictory and unscreened passages remain distinguishable in the augmented context; unscreened documents are not represented as verified evidence.

## Streaming and failure behavior

Jev makes structured judgments; it does not generate prose or stream tokens. Both self-refinement and guardrails therefore need a completed answer. When either complete-answer policy is enabled, the gateway uses the official `call()` path and emits the accepted answer as one SSE token after evaluation. Without those policies, Spring AI's incremental streaming path remains in use.

TypeSafe's official document filter may leave a passage unclassified when it cannot screen it. These passages are explicitly labelled `UNSCREENED` in the augmented context rather than silently treated as supporting evidence. Tool Calling, tool-call limits, provider selection, and tool callback execution remain managed by Spring AI.

## Upstream references

- [Spring AI and TypeSafe Jev: Fast, Cheap, Structured Decisions](https://spring.io/blog/2026/09/21/spring-ai-typesafe-structured-judgment/)
- [Spring AI Community TypeSafe repository, v0.4.0](https://github.com/spring-ai-community/spring-ai-typesafe/tree/v0.4.0)
- [Spring AI 2.0.1 reference](https://docs.spring.io/spring-ai/reference/)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring Boot auto-configuration guidance](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
