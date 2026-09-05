package com.marketplace.media;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the media layer (roadmap B1 / G-PROD-1): pins the wiring that
 * makes the capability real and honestly inert. Same class of latent
 * "config that lies" defect as {@code ObservationCoverageFilesTest}: nothing at
 * runtime rejects a renamed env placeholder or a dropped module wiring — only
 * a pinned test keeps them honest.
 *
 * <p>Pinned contract:
 * <ul>
 *   <li>application.yml — the four MEDIA_S3_* provider-gate placeholders exist
 *       (empty defaults = module inert, 503 SU-001) plus the limits section</li>
 *   <li>parent pom — the 17th module, the awssdk BOM import (Exception #10) and
 *       the dependencyManagement entry</li>
 *   <li>marketplace-media pom — the two AWS artifacts (s3 + the JDK HTTP
 *       implementation for HeadObject)</li>
 *   <li>marketplace-app pom — the composition root carries the module</li>
 *   <li>V32 — the media_assets table, its unique object_key, the display-order
 *       index and the Envers audit table</li>
 *   <li>package-info — the module depends only on the three shared named
 *       interfaces (no new shared surface: listing ownership reuses the
 *       existing ListingPriceProvider / ProviderLookupPort ports)</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class MediaFilesTest {

    private Path repoRoot() {
        Path fromModule = Paths.get("../");
        if (Files.isDirectory(fromModule.resolve(".github"))) {
            return fromModule;
        }
        return Paths.get(".");
    }

    private String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative));
    }

    @Test
    void applicationYmlCarriesTheProviderGatePlaceholders() throws IOException {
        String yml = read("marketplace-app/src/main/resources/application.yml");
        Map<String, Object> root = new Yaml().load(yml);

        @SuppressWarnings("unchecked")
        Map<String, Object> marketplace = (Map<String, Object>) root.get("marketplace");
        assertThat(marketplace).as("marketplace section must exist").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) marketplace.get("media");
        assertThat(media).as("marketplace.media section must exist").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> storage = (Map<String, Object>) media.get("storage");
        assertThat(storage).isNotNull();
        assertThat(storage.get("endpoint"))
                .as("MEDIA_S3_ENDPOINT placeholder (empty default = module inert)")
                .isEqualTo("${MEDIA_S3_ENDPOINT:}");
        assertThat(storage.get("bucket")).isEqualTo("${MEDIA_S3_BUCKET:}");
        assertThat(storage.get("access-key")).isEqualTo("${MEDIA_S3_ACCESS_KEY:}");
        assertThat(storage.get("secret-key")).isEqualTo("${MEDIA_S3_SECRET_KEY:}");
        assertThat(storage.get("region"))
                .as("region auto is the documented R2 convention")
                .isEqualTo("${MEDIA_S3_REGION:auto}");

        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) media.get("limits");
        assertThat(limits).isNotNull();
        assertThat(limits.get("max-upload-bytes")).isEqualTo("${MEDIA_MAX_UPLOAD_BYTES:10485760}");
        assertThat(limits.get("presign-ttl")).isEqualTo("${MEDIA_PRESIGN_TTL:15m}");
    }

    @Test
    void parentPomWiresModuleBomAndDependencyManagement() throws IOException {
        String pom = read("pom.xml");
        assertThat(pom).contains("<module>marketplace-media</module>");
        assertThat(pom)
                .as("awssdk BOM import (Exception #10) keeps every AWS artifact on one version")
                .contains("<artifactId>bom</artifactId>")
                .contains("<awssdk.version>2.54.13</awssdk.version>");
        assertThat(pom)
                .as("internal module must be managed like every other module")
                .contains("<artifactId>marketplace-media</artifactId>");
    }

    @Test
    void mediaPomCarriesTheOfficialS3ChannelArtifacts() throws IOException {
        String pom = read("marketplace-media/pom.xml");
        assertThat(pom)
                .as("AWS SDK v2 s3 provides S3Presigner (presigning) and S3Client (HeadObject verification)")
                .contains("<artifactId>s3</artifactId>");
        assertThat(pom)
                .as("the JDK-based HTTP implementation for the single network call")
                .contains("<artifactId>url-connection-client</artifactId>");
    }

    @Test
    void appPomComposesTheModule() throws IOException {
        String pom = read("marketplace-app/pom.xml");
        assertThat(pom).contains("<artifactId>marketplace-media</artifactId>");
    }

    @Test
    void migrationV32OwnsTheSchemaWithAuditAndOrder() throws IOException {
        String sql = read("marketplace-app/src/main/resources/db/migration/V32__media_assets.sql");
        assertThat(sql).contains("CREATE TABLE media_assets");
        assertThat(sql)
                .as("object_key is server-generated and must stay unique")
                .contains("uq_media_assets_object_key UNIQUE (object_key)");
        assertThat(sql)
                .as("display order index over live rows only")
                .contains("idx_media_assets_listing_position")
                .contains("WHERE is_deleted = FALSE");
        assertThat(sql)
                .as("Envers audit history follows the V24 convention")
                .contains("CREATE TABLE media_assets_aud");
    }

    @Test
    void moduleBoundariesMatchTheHousePattern() throws IOException {
        String packageInfo = read("marketplace-media/src/main/java/com/marketplace/media/package-info.java");
        assertThat(packageInfo)
                .as("the media module may only depend on the three shared named interfaces")
                .contains("allowedDependencies = {\"shared :: shared-api\", \"shared :: shared-security\", \"shared :: shared-jpa\"}");
    }
}
