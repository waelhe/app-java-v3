# Airbnb 26.42 — Reverse-Engineering Research

Static, offline analysis of the Airbnb Android package `Airbnb_26.42.apks`
(`package com.airbnb.android`, versionName `26.42`, versionCode `28026652`).

- **Scope:** container format, manifest/security surface, resources + design
  system (DLS), UI/UX architecture, navigation, feature inventory, third-party
  SDK inventory, native libraries, storage, telemetry.
- **Method:** fully static. No app execution, no network, no live targets.
  Container cracked by parsing local ZIP headers manually (the `.apks` stream
  has its central directory and EOCD stripped).
- **Artifact digest (SHA-256):**
  `95D5B45BF4C3ED5E8807554513CEA07DA5573D218041A670B53A9DD81C92E920`

## Contents

| File | Description |
| --- | --- |
| `report-ar.md` | Full report (Arabic) — 13 sections, end-to-end. |
| `manifest_components.json` | Parsed AndroidManifest component map (name / exported / launchMode / intent filters). |
| `outer_extract.py` | Rebuilds the streamed `.apks` container and extracts its 5 entries. |
| `manifest_profile.py` | Extracts the manifest security/component profile. |
| `elf_profile.py` | Native library profiling (ELF/imports/strings). |
| `syms.py` | Symbol/string extraction helper for native libraries. |
| `ar_strings.py` | Arabic string-resource extraction from the `ar` split. |
| `ui_profile.py` | UI/resource scan (layouts, styles, dimens, colors). |

## Not included

Large binaries and decoded trees are intentionally excluded: source `*.apk`
splits, `jadx_out/` (42,555 `.java`) and the apktool decoded trees.

## Notes

- The package ships **English + Arabic** only; full RTL support is present.
- TLS certificate pinning is **capable but not configured** (no `sha256/` pins).
- App code is fully R8-obfuscated into `defpackage`; no mapping file is shipped.
