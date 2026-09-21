import { defineRailway, github, preserve, project, service } from "railway/iac";

// Migrated from railway.toml (Config as Code, deprecated — hard cutoff
// 2026-12-01) per docs.railway.com/infrastructure-as-code#migrating-from-config-as-code.
//
// Partial export: this file manages ONLY the app-java-v3 service (the only
// CaC-managed service). The data services are external channels since the
// Neon migration (2026-09-18) and stay outside this partial by design.
//
// Reconciled 2026-09-19 for the v3-account migration (PR #347, SYSTEM.md §15):
//   - source: the DIRECT main repository (waelhe/app-java-v3, branch main) —
//     the retired deployment fork (waelhe88-coder/app-java-v3) was left here
//     by the pre-migration state, and any `railway config apply` with it
//     would rebind this service to a repository whose sync workflow is
//     disabled and whose account is drained (the CodeRabbit-flagged trap).
//   - env: the full measured live set — 45 service-scoped names on the v3
//     service's production environment (set-equality verified against the
//     GraphQL enumeration after the faithful transfer; the 2026-09-13
//     declaration had 35 names — the S3 media bundle, the two IP hash keys,
//     SPRING_AI_MODEL_CHAT, MAIL_PORT and SPRING_DATA_REDIS_SSL_ENABLED were
//     added since). All via preserve() — the official mechanism that keeps
//     values managed in Railway (secrets never materialize here).
//   - volumeMounts: REMOVED — the v3 service runs without a volume (the
//     /data "app-java-v3-volume" residue belonged to the retired account's
//     service; the v3 deployment 4b203ec2 is measured healthy without it).
//     Declaring it here would re-create the old topology on apply.
//   - healthcheck: unchanged ([deploy] semantics — service-level settings
//     that actually reflect in deploy manifests).
//   - no config apply has ever run against the v3 project; this file is the
//     declared record matching the API-applied live state.
export const partial = "app-java-v3";

export default defineRailway(() => {
  const app_java_v3 = service("app-java-v3", {
    source: github("waelhe/app-java-v3", { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "Dockerfile",
    },
    healthcheck: "/actuator/health/liveness",
    healthcheckTimeout: 300,
    env: {
      AUTH_SERVER_ISSUER: preserve(),
      CORS_ALLOWED_ORIGINS: preserve(),
      DB_PASSWORD: preserve(),
      DB_URL: preserve(),
      DB_USERNAME: preserve(),
      EXCHANGE_BASE_CURRENCY: preserve(),
      JWT_KEYSTORE_B64: preserve(),
      JWT_KEYSTORE_PASSWORD: preserve(),
      JWT_KEY_ALIAS: preserve(),
      JWT_KEY_PASSWORD: preserve(),
      MAIL_HOST: preserve(),
      MAIL_PASSWORD: preserve(),
      MAIL_PORT: preserve(),
      MAIL_USERNAME: preserve(),
      MANAGEMENT_SERVER_PORT: preserve(),
      MARKETPLACE_CATALOG_VIEWS_IP_HASH_KEY: preserve(),
      MARKETPLACE_MESSAGING_LEADS_IP_HASH_KEY: preserve(),
      MARKETPLACE_PAYMENTS_WEBHOOK_SHARED_SECRET: preserve(),
      MARKETPLACE_PRICING_CURRENCY_EXCHANGE_RATES_AED: preserve(),
      MARKETPLACE_PRICING_CURRENCY_EXCHANGE_RATES_EGP: preserve(),
      MARKETPLACE_PRICING_CURRENCY_EXCHANGE_RATES_EUR: preserve(),
      MARKETPLACE_PRICING_CURRENCY_EXCHANGE_RATES_GBP: preserve(),
      MARKETPLACE_PRICING_CURRENCY_EXCHANGE_RATES_KWD: preserve(),
      MARKETPLACE_PRICING_CURRENCY_EXCHANGE_RATES_USD: preserve(),
      MEDIA_S3_ACCESS_KEY: preserve(),
      MEDIA_S3_BUCKET: preserve(),
      MEDIA_S3_ENDPOINT: preserve(),
      MEDIA_S3_REGION: preserve(),
      MEDIA_S3_SECRET_KEY: preserve(),
      OAUTH_CLIENT_ID: preserve(),
      OAUTH_CLIENT_REDIRECT_URIS: preserve(),
      OAUTH_CLIENT_SECRET: preserve(),
      OAUTH_PUBLIC_CLIENT_ID: preserve(),
      OAUTH_PUBLIC_CLIENT_REDIRECT_URIS: preserve(),
      OTEL_METRICS_EXPORT_URL: preserve(),
      OTEL_TRACES_EXPORT_URL: preserve(),
      PSEUDONYMIZATION_HMAC_KEY: preserve(),
      REDIS_HOST: preserve(),
      REDIS_PORT: preserve(),
      SPRING_AI_MODEL_CHAT: preserve(),
      SPRING_DATA_REDIS_PASSWORD: preserve(),
      SPRING_DATA_REDIS_SSL_ENABLED: preserve(),
      SPRING_FLYWAY_PASSWORD: preserve(),
      SPRING_FLYWAY_USER: preserve(),
      SPRING_PROFILES_ACTIVE: preserve(),
    },
  });

  const app_java_v3_edge = service("app-java-v3-edge", {
    source: github("waelhe/app-java-v3", { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "marketplace-edge/Dockerfile",
    },
    healthcheck: "/actuator/health/liveness",
    healthcheckTimeout: 300,
    env: {
      AUTH_SERVER_ISSUER: preserve(),
      EDGE_BACKEND_URL: preserve(),
      EDGE_CLIENT_ID: preserve(),
      EDGE_CLIENT_SECRET: preserve(),
      REDIS_HOST: preserve(),
      REDIS_PORT: preserve(),
      SPRING_DATA_REDIS_PASSWORD: preserve(),
      SPRING_DATA_REDIS_SSL_ENABLED: preserve(),
      SPRING_PROFILES_ACTIVE: preserve(),
    },
  });

  return project("app-java-v3", {
    resources: [app_java_v3, app_java_v3_edge],
  });
});
