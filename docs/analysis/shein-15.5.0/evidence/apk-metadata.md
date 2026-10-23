# APK metadata and build inventory

## Artifact identity

| Field | Value |
|---|---|
| Package | `com.zzkko` |
| Version name | `15.5.0` |
| Version code | `2941` |
| Min SDK | `23` |
| Target SDK | `36` |
| Compile SDK | `36` (Android 16 codename) |
| `supportsRtl` | `true` |
| UI toolkit | Native Android Views + XML |
| APKM size | 48,212,198 bytes (45.98 MiB) |
| Base APK size | 82,809,065 bytes (78.97 MiB) |
| APKM SHA-256 | `D130C3A3436561E57F75C3160C011753FA5683FB375BA955D7542383265A2E0A` |
| Base APK SHA-256 | `C4AC56B8292D372661F016B91BD19B6136BF61E8D0C465C87EEC0B6A5F512D26` |
| APKM source | `C:\Users\w-co\Downloads\com.zzkko_15.5.0-2941_4arch_7dpi_2feat_376274777b445dc78f9dadb972b60112_apkmirror.com.apkm` |
| Decoded tree | `C:\Users\w-co\AppData\Local\Temp\opencode\zzkko-apktool` |
| apktool | `3.0.3` |

The APKM is a split bundle; `base.apk` is the base split extracted from it. The base APK contains the base resources and code; ABI and density splits are separate. No native `lib/` tree is present in the base split.

## Resource counts

| Collection | Count | Note |
|---|---:|---|
| `res/layout` files | 3,721 | Native XML layouts |
| Drawable files | 3,302 | Across 12 `drawable*` directories |
| `res/values/colors.xml` entries | 1,648 | Includes duplicated hex values |
| `res/values/dimens.xml` entries | 1,129 | 875 are `dp` values |
| `res/values/styles.xml` entries | 1,139 | Styles and derived themes |
| `res/values-*` directories | 94 | Excludes the base `values/` directory; includes `values-ar` and `values-night` |
| `res/font` files | 17 | 4 font binaries, 3 GMS XML families, 10 weight XML files |
| Arabic resource directory | 1 | `values-ar` |
| Night resource directory | 1 | `values-night` |

## Manifest observations

- `android:allowBackup="false"`.
- `android:enableOnBackInvokedCallback="false"`; the app opts out of the predictive-back callback path on this build.
- `android:largeHeap="true"`.
- `android:requestLegacyExternalStorage="true"` (legacy flag still present; target SDK 36 makes it ineffective for scoped storage).
- `android:supportsRtl="true"`.
- The application class is `com.zzkko.app.ZzkkoApplication`.
- The manifest uses `android:requiredSplitTypes="base__abi,base__density"`, confirming split delivery.

## Caveats

- Resource names are obfuscated in the decoded tree (`APKTOOL_RENAMED_*`), so semantic names cannot be inferred from the APK alone.
- The base APK is not the complete install set; density/ABI splits matter for runtime assets.
- The package is from APKMirror and is not a Play Store install; signature verification and update-channel behavior were not audited.
- `versionName` and `versionCode` come from `apktool.yml`; the binary manifest carries `compileSdkVersion` and package identity.
