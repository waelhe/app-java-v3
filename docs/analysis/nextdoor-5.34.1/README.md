# Nextdoor 5.34.1 — Static Analysis Corpus

Analysis corpus for the **Nextdoor** Android app, version **5.34.1** (`versionCode 1254037`,
arm64-v8a, `minSdk 32`), performed end-to-end from the shipped artifact only.

This folder is a **reference/analysis archive**. It is not part of the marketplace backend
domain and adds no production code, dependency, or migration.

## Artifact identity

| Field | Value |
| --- | --- |
| Package | `com.nextdoor` |
| Version | 5.34.1 (`1254037`) |
| Source | APKMirror `.apkm` bundle (`1arch · 2dpi · 8lang`) |
| `base.apk` SHA-256 | `7cb7ae84815cf399c0070354aaafb97f02429eb2785f93273b40e1c888adeb12` |
| `.apkm` SHA-256 | `300402c56784d8943c5a126be788ca46fa86c319cbdfee7a459aa63c9db64e5f` |
| Obfuscation | R8, partial (native libs in splits) |

## Method

- `apktool 3.0.3 d --no-src` for resources, manifest, and token extraction.
- A custom DEX parser for class/field/method/string/Composable inventory (JADX headless MCP was
  unusable for corpus-wide queries — request timeouts), applied to the 10 `classes*.dex`.
- SBOM reconstruction from DEX/POM/native artifacts, then OSV + GitHub Advisory + NVD lookup
  with pinned, hash-recorded query/response pairs.
- Live device session on a **physical arm64 device** (Android 16 / SDK 36, Play-installed
  **5.33.4**) for authenticated UI/UX validation. The x86_64 emulator was rejected: an
  arm64-only app run through `libndk_translation.so` fails Android's process-start watchdog.

## Reports (`reports/`)

| Report | Scope |
| --- | --- |
| `Nextdoor_5.34.1_UI_UX_study.md` | UI/UX end-to-end study: information architecture, screen inventory, the "Blocks" design system, states, accessibility, i18n, heuristic findings, recommendations. |
| `Nextdoor_5.34.1_SBOM_CVE_report.md` | CycloneDX-style SBOM + CVE/vulnerability triage (OSV, GitHub Advisory, NVD). |
| `Nextdoor_5.34.1_exported_surface_security_audit.md` | Exported-components and intent/attack-surface audit. |
| `Nextdoor_5.34.1_dex_supplychain_report.md` | DEX-level supply-chain / third-party SDK inventory. |
| `Nextdoor_5.34.1-1254037_apkmirror_report.md` | Artifact provenance and split inventory. |

## Evidence (`evidence/`)

| File | Content |
| --- | --- |
| `manifest.utf8.txt` | Decoded `AndroidManifest.xml`. |
| `tokens_colors.txt` / `tokens_colors_night.txt` | Color tokens (light / `values-night`). |
| `tokens_dimens.txt` / `tokens_styles.txt` | Dimen and style/text-appearance tokens. |
| `dex_composables.txt` | Jetpack Compose `@Composable` inventory. |
| `screens.txt` | Derived screen (Activity/Sheet) inventory. |
| `sbom.json` / `sbom_table.txt` / `sbom_raw.txt` | Reconstructed SBOM in three forms. |
| `osv_querybatch.json` / `osv_history.json` / `osv_resp.json` / `osv_control*.json` | Pinned vulnerability-query inputs/outputs (audit trail). |

## Limitations

- Static analysis: no runtime attestation beyond the live device session noted above.
- Live device version was 5.33.4 (store-pinned), one patch behind the static 5.34.1 target.
- Native binaries inside splits are analyzed structurally only; `libquickjs.so` and the R8-mangled
  `protobuf-javalite` remain partially unresolved.
