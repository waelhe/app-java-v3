import { defineRailway, github, preserve, project, service, volume } from "railway/iac";

// Migrated from railway.toml (Config as Code, deprecated — hard cutoff
// 2026-12-01) per docs.railway.com/infrastructure-as-code#migrating-from-config-as-code.
//
// Partial export: this file manages ONLY the app-java-v3 service (the only
// CaC-managed service). The data services (postgres-18, postgres, redis,
// netdiag) are image-based, not CaC-managed, and stay Railway-managed.
//
// Everything the service owns is DECLARED so the partial's remove-what-is-
// omitted semantics cannot destroy state:
//   - source: the deploy GitHub repo (fork waelhe88-coder/app-java-v3, main)
//   - build: builder DOCKERFILE + root Dockerfile (expressed explicitly per
//     the DSL types — the migrate tool emitted them as comments only; losing
//     builder=DOCKERFILE would drop builds to Railpack's Java 21 default and
//     re-break the JDK 25 build, the documented 9dbbbb5 failure mode)
//   - env: all 26 runtime variables via preserve() — the official mechanism
//     that keeps values managed in Railway (secrets never materialize here)
//   - volumeMounts: the existing app-java-v3-volume at /data (the documented
//     residue — its separation/removal stays a user-gated decision, not an
//     omission side effect)
//   - healthcheck: the [deploy] section of the old CaC file — now service-
//     level settings that actually reflect in deploy manifests (closing the
//     documented gap where file-level [deploy] never applied).
export const partial = "app-java-v3";

export default defineRailway(() => {
  const app_data_volume = volume("app-java-v3-volume", { region: "ams", sizeMB: 500 });

  const app_java_v3 = service("app-java-v3", {
    source: github("waelhe88-coder/app-java-v3", { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "Dockerfile",
    },
    healthcheck: "/actuator/health/liveness",
    healthcheckTimeout: 300,
    volumeMounts: {
      "/data": app_data_volume,
    },
    env: {
      AUTH_SERVER_ISSUER: preserve(),
      CORS_ALLOWED_ORIGINS: preserve(),
      DB_PASSWORD: preserve(),
      DB_URL: preserve(),
      DB_USERNAME: preserve(),
      JWT_KEYSTORE_B64: preserve(),
      JWT_KEYSTORE_PASSWORD: preserve(),
      JWT_KEYSTORE_PATH: preserve(),
      JWT_KEY_ALIAS: preserve(),
      JWT_KEY_PASSWORD: preserve(),
      MAIL_HOST: preserve(),
      MAIL_PASSWORD: preserve(),
      MAIL_USERNAME: preserve(),
      MANAGEMENT_SERVER_PORT: preserve(),
      MARKETPLACE_PAYMENTS_WEBHOOK_SHARED_SECRET: preserve(),
      OAUTH_CLIENT_ID: preserve(),
      OAUTH_CLIENT_REDIRECT_URIS: preserve(),
      OAUTH_CLIENT_SECRET: preserve(),
      OAUTH_PUBLIC_CLIENT_ID: preserve(),
      OAUTH_PUBLIC_CLIENT_REDIRECT_URIS: preserve(),
      OTEL_METRICS_EXPORT_URL: preserve(),
      OTEL_TRACES_EXPORT_URL: preserve(),
      REDIS_HOST: preserve(),
      REDIS_PORT: preserve(),
      SPRING_DATA_REDIS_PASSWORD: preserve(),
      SPRING_PROFILES_ACTIVE: preserve(),
    },
  });

  return project("app-java-v3", {
    resources: [app_java_v3, app_data_volume],
  });
});
