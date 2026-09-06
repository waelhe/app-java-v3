package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Prometheus alert rules committed at
 * {@code monitoring/prometheus-rules/marketplace-alerts.yml}:
 *
 * <ul>
 *   <li>syntactic YAML validity and structural completeness — every rule must carry
 *       {@code alert}, single-line {@code expr}, {@code for}, {@code labels.severity}
 *       (critical/warning) and {@code annotations.summary/description};</li>
 *   <li>metric provenance — every metric identifier referenced by an expression must
 *       be part of {@link #KNOWN_METRICS}: the whitelist of metrics proven to exist
 *       at runtime, each with a file:line reference documented in
 *       {@code docs/observability/slo.md} §4. A rule referencing an unproven metric
 *       would never fire and silently break the alerting contract.</li>
 * </ul>
 */
class AlertRulesYamlTest {

    private static final Path RULES_FILE =
            Path.of("..", "monitoring", "prometheus-rules", "marketplace-alerts.yml");

    /** Metrics proven to exist at runtime — provenance: docs/observability/slo.md §4. */
    private static final Set<String> KNOWN_METRICS = Set.of(
            "up",
            "http_server_requests_seconds_count",
            "marketplace_payments_failed_total",
            "marketplace_payments_initiated_total",
            "marketplace_payments_completed_total",
            "marketplace_cache_invalidation_evict_failure_total",
            "marketplace_eventbus_stale",
            "resilience4j_circuitbreaker_state",
            "hikaricp_connections_active",
            "hikaricp_connections_max"
    );

    /** PromQL functions/keywords allowed to appear in expressions. */
    private static final Set<String> PROMQL_KEYWORDS = Set.of(
            "sum", "rate", "increase", "avg", "min", "max", "count",
            "clamp_min", "clamp_max", "by", "without", "offset",
            "ignoring", "on", "group_left", "group_right", "and", "or", "unless"
    );

    @Test
    @SuppressWarnings("unchecked")
    void alertRulesAreStructurallyComplete() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(RULES_FILE));

        assertThat(root).isNotNull();
        assertThat(root).containsKey("groups");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertThat(groups).isNotEmpty();

        for (Map<String, Object> group : groups) {
            assertThat(group.get("name")).asString().isNotBlank();
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            assertThat(rules).isNotEmpty();
            for (Map<String, Object> rule : rules) {
                assertThat(rule).containsKeys("alert", "expr", "for", "labels", "annotations");
                Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
                assertThat(labels.get("severity")).isIn("critical", "warning");
                Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                assertThat(annotations).containsKeys("summary", "description");
            }
        }
    }

    @Test
    void alertRulesReferenceOnlyProvenMetrics() throws IOException {
        String raw = Files.readString(RULES_FILE);
        String withoutComments = raw.replaceAll("(?m)^\\s*#.*$", "");

        Set<String> referenced = new HashSet<>();
        Matcher exprLine = Pattern.compile("(?m)^\\s*expr:\\s*(.+)$").matcher(withoutComments);
        while (exprLine.find()) {
            String expression = exprLine.group(1)
                    .replaceAll("\\{[^}]*}", " ")   // label selectors
                    .replaceAll("\\[[^]]*]", " ");  // range selectors like [10m]
            Matcher identifier = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_:]*").matcher(expression);
            while (identifier.find()) {
                referenced.add(identifier.group());
            }
        }
        assertThat(referenced).isNotEmpty();

        Set<String> unknown = referenced.stream()
                .filter(id -> !KNOWN_METRICS.contains(id) && !PROMQL_KEYWORDS.contains(id))
                .collect(Collectors.toSet());
        assertThat(unknown)
                .as("alert rules reference metrics that are not in the proven whitelist "
                        + "(see docs/observability/slo.md §4 — add provenance before whitelisting)")
                .isEmpty();
    }
}
