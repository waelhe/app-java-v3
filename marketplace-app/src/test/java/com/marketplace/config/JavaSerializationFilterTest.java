package com.marketplace.config;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the JEP 290 deny rule protecting the relocated
 * Guava collection classes embedded in the pinned GraphQL Java artifact.
 *
 * <p>The runtime Docker image and both forked Maven test lifecycles must use
 * the same process-wide filter. The test evaluates the actual relocated
 * MapMakerInternalMap class shipped by GraphQL Java, not a synthetic class.
 */
class JavaSerializationFilterTest {

    private static final String FILTER =
            "!graphql.com.google.common.collect.**;*";

    @Test
    void processWideFilterRejectsGraphqlJavaRelocatedMapMakerInternalMap() throws Exception {
        assertThat(System.getProperty("jdk.serialFilter"))
                .as("Surefire and Failsafe must use the same JEP 290 filter as production")
                .isEqualTo(FILTER);

        ObjectInputFilter filter = ObjectInputFilter.Config.getSerialFilter();
        assertThat(filter).as("the JVM-wide serialization filter must be active").isNotNull();

        Class<?> relocatedMapMaker = Class.forName(
                "graphql.com.google.common.collect.MapMakerInternalMap",
                false,
                Thread.currentThread().getContextClassLoader());

        assertThat(statusFor(filter, relocatedMapMaker))
                .as("the shaded Guava deserialization gadget must be rejected")
                .isEqualTo(ObjectInputFilter.Status.REJECTED);
        assertThat(statusFor(filter, String.class))
                .as("ordinary application/JDK value types remain allowed")
                .isEqualTo(ObjectInputFilter.Status.ALLOWED);
    }

    @Test
    void runtimeDockerAndBothMavenTestForksCarryTheSameFilter() throws IOException {
        Path root = repositoryRoot();
        String dockerfile = Files.readString(root.resolve("Dockerfile"));
        int runtimeStart = dockerfile.lastIndexOf("FROM eclipse-temurin:25-jre-alpine");
        assertThat(runtimeStart).as("the production JRE runtime stage must exist")
                .isGreaterThanOrEqualTo(0);
        String runtimeStage = dockerfile.substring(runtimeStart);
        assertThat(runtimeStage).contains("-Djdk.serialFilter=" + FILTER);
        assertThat(dockerfile.substring(0, runtimeStart))
                .doesNotContain("-Djdk.serialFilter=");

        String pom = Files.readString(root.resolve("pom.xml"));
        assertThat(countOccurrences(pom, "-Djdk.serialFilter=" + FILTER))
                .as("Surefire and Failsafe each run with the production filter")
                .isEqualTo(2);
    }

    private static ObjectInputFilter.Status statusFor(ObjectInputFilter filter, Class<?> type) {
        return filter.checkInput(new ObjectInputFilter.FilterInfo() {
            @Override
            public Class<?> serialClass() {
                return type;
            }

            @Override
            public long arrayLength() {
                return -1;
            }

            @Override
            public long depth() {
                return 1;
            }

            @Override
            public long references() {
                return 1;
            }

            @Override
            public long streamBytes() {
                return 1;
            }
        });
    }

    private static Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("Dockerfile"))
                    && Files.isRegularFile(candidate.resolve(".trivyignore.yaml"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate the app-java-v3 repository root");
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
