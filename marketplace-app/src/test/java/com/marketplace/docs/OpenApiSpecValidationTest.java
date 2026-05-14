package com.marketplace.docs;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSpecValidationTest {

    private static final Set<String> EXPECTED_PATHS = Set.of(
            "/api/v1/users/me",
            "/api/v1/providers",
            "/api/v1/providers/{id}",
            "/api/v1/admin/providers/{id}/verify",
            "/api/v1/admin/providers/{id}/suspend",
            "/api/v1/listings",
            "/api/v1/listings/{id}",
            "/api/v1/listings/category/{category}",
            "/api/v1/listings/provider/{providerId}",
            "/api/v1/listings/{id}/activate",
            "/api/v1/listings/{id}/pause",
            "/api/v1/listings/{id}/archive",
            "/api/v1/bookings",
            "/api/v1/bookings/{id}",
            "/api/v1/bookings/consumer/{consumerId}",
            "/api/v1/bookings/provider/{providerId}",
            "/api/v1/bookings/{id}/confirm",
            "/api/v1/bookings/{id}/complete",
            "/api/v1/bookings/{id}/cancel",
            "/api/v1/payments/intents",
            "/api/v1/payments/intents/{id}",
            "/api/v1/payments/intents/{id}/process",
            "/api/v1/payments/intents/{id}/confirm",
            "/api/v1/payments/intents/{id}/cancel",
            "/api/v1/payments/{paymentId}/refund",
            "/api/v1/payments/webhooks/{provider}",
            "/api/v1/reviews",
            "/api/v1/reviews/{id}",
            "/api/v1/reviews/provider/{providerId}",
            "/api/v1/reviews/reviewer/{reviewerId}",
            "/api/v1/search",
            "/api/v1/search/category/{category}",
            "/api/v1/providers/{providerId}/availability",
            "/api/v1/providers/{providerId}/availability/slots",
            "/api/v1/providers/{providerId}/availability/rules",
            "/api/v1/providers/{providerId}/time-off",
            "/api/v1/notifications",
            "/api/v1/notifications/{id}/read",
            "/api/v1/admin/ledger/providers/{providerId}/balance",
            "/api/v1/admin/ledger/providers/{providerId}/credit",
            "/api/v1/bookings/{bookingId}/disputes",
            "/api/v1/admin/disputes/{id}/resolve",
            "/api/v1/messages/conversations",
            "/api/v1/messages/conversations/{id}",
            "/api/v1/messages/conversations/{conversationId}/messages",
            "/api/v1/messages/conversations/{conversationId}/unread",
            "/api/v1/messages/conversations/{conversationId}/read",
            "/api/v1/admin/users",
            "/api/v1/admin/listings",
            "/api/v1/admin/listings/{id}/archive",
            "/api/v1/admin/bookings",
            "/api/v1/admin/payments",
            "/api/v1/admin/payments/{id}"
    );

    private static final Set<String> EXPECTED_SCHEMAS = Set.of(
            "UserResponse", "UserSummary",
            "ProviderRequest", "ProviderResponse", "ProviderStatus", "ProviderSummary",
            "CreateListingRequest", "UpdateListingRequest", "ListingResponse", "ListingSummary",
            "ProviderListingSummary", "ListingStatus",
            "CreateBookingRequest", "BookingResponse", "BookingSummary", "BookingStatus",
            "CreateIntentRequest", "ConfirmIntentRequest", "PaymentIntentResponse",
            "PaymentResponse", "PaymentSummary", "PaymentStatus", "PaymentIntentStatus",
            "CreateReviewRequest", "UpdateReviewRequest", "ReviewResponse",
            "CreateConversationRequest", "ConversationResponse", "SendMessageRequest",
            "MessageResponse", "UnreadCountResponse",
            "AvailabilitySlotRequest", "AvailabilitySlot", "AvailabilityRuleRequest",
            "ProviderAvailabilityRule", "TimeOffRequest", "ProviderTimeOff",
            "Notification",
            "LedgerEntry", "LedgerEntryType", "ProviderBalance",
            "Dispute", "DisputeCreateRequest", "DisputeStatus",
            "PagedResponse_UserSummary", "PagedResponse_ListingSummary",
            "PagedResponse_ListingResponse", "PagedResponse_BookingResponse",
            "PagedResponse_BookingSummary", "PagedResponse_PaymentSummary",
            "PagedResponse_ProviderListingSummary", "PagedResponse_ReviewResponse",
            "PagedResponse_MessageResponse",
            "ErrorResponse", "FieldError"
    );

    // Paths expected to define POST (mutations) that don't return 200
    private static final Set<String> PATHS_WITH_204 =
            Set.of("/api/v1/messages/conversations/{conversationId}/read");

    @Test
    void openApiSpecShouldContainRequiredTopLevelSections() {
        Map<String, Object> doc = loadDoc();

        assertThat(doc).containsKeys("openapi", "info", "paths", "components");
        assertThat(doc.get("openapi")).isEqualTo("3.1.0");

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        assertThat(components).containsKeys("securitySchemes", "schemas", "parameters");
    }

    @Test
    void allExpectedPathsShouldExist() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");

        Set<String> actualPaths = new HashSet<>(paths.keySet());
        assertThat(actualPaths)
                .as("All expected OpenAPI paths should be documented")
                .containsAll(EXPECTED_PATHS);
    }

    @Test
    void allExpectedSchemasShouldExist() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        Set<String> actualSchemas = new HashSet<>(schemas.keySet());
        assertThat(actualSchemas)
                .as("All expected schemas should be defined in components/schemas")
                .containsAll(EXPECTED_SCHEMAS);
    }

    @Test
    void allSchemaRefsMustResolveToExistingSchemas() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) components.get("parameters");

        Set<String> allComponentNames = new HashSet<>();
        if (schemas != null) allComponentNames.addAll(schemas.keySet());
        if (parameters != null) allComponentNames.addAll(parameters.keySet());

        Set<String> refs = new HashSet<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String pathName = pathEntry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> methods = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> methodEntry : methods.entrySet()) {
                collectRefs(methodEntry.getValue(), pathName + "." + methodEntry.getKey(), refs);
            }
        }

        for (String ref : refs) {
            String name = ref.replace("#/components/schemas/", "")
                             .replace("#/components/parameters/", "");
            if (!allComponentNames.contains(name)) {
                throw new AssertionError(
                        "Unresolvable $ref: '" + ref + "' — no such component '" + name + "'");
            }
        }
    }

    @Test
    void eachPathShouldHaveAtLeastOneResponseWithDescription() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String pathName = pathEntry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> methods = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> methodEntry : methods.entrySet()) {
                String httpMethod = methodEntry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> operation =
                        (Map<String, Object>) methodEntry.getValue();

                assertThat(operation)
                        .as("Operation %s %s must have a summary", httpMethod.toUpperCase(), pathName)
                        .containsKey("summary");

                @SuppressWarnings("unchecked")
                Map<String, Object> responses =
                        (Map<String, Object>) operation.get("responses");

                assertThat(responses)
                        .as("Operation %s %s must define responses", httpMethod.toUpperCase(), pathName)
                        .isNotEmpty();

                boolean hasValidStatus = responses.containsKey("200") || responses.containsKey("204");
                assertThat(hasValidStatus)
                        .as("Operation %s %s must define 200 or 204 response", httpMethod.toUpperCase(), pathName)
                        .isTrue();
            }
        }
    }

    @Test
    void pagedResponsesShouldContainArrayContent() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>)
                ((Map<String, Object>) doc.get("components")).get("schemas");

        for (Map.Entry<String, Object> entry : schemas.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("PagedResponse_")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked")
                Map<String, Object> properties =
                        (Map<String, Object>) schema.get("properties");

                assertThat(properties)
                        .as("PagedResponse schema '%s' must have properties", name)
                        .containsKeys("content", "pageNumber", "pageSize", "totalElements", "totalPages", "last");

                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) properties.get("content");
                assertThat(content).containsKey("items");
                assertThat(content.get("type")).isEqualTo("array");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectRefs(Object node, String context, Set<String> refs) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            if (map.containsKey("$ref")) {
                refs.add((String) map.get("$ref"));
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                collectRefs(entry.getValue(), context + "." + entry.getKey(), refs);
            }
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                collectRefs(list.get(i), context + "[" + i + "]", refs);
            }
        }
    }

    private Map<String, Object> loadDoc() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("openapi/marketplace-openapi.yaml")) {
            assertThat(in).isNotNull();
            return yaml.load(in);
        } catch (Exception ex) {
            throw new AssertionError("OpenAPI spec parsing failed", ex);
        }
    }
}
