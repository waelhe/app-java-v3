#!/usr/bin/env bash
set -euo pipefail

: "${API_BASE_URL:?Provide API_BASE_URL as an HTTPS base URL}"
: "${OIDC_ISSUER:?Provide OIDC_ISSUER as the exact HTTPS issuer}"
: "${PASSKEY_RP_ID:?Provide PASSKEY_RP_ID as a DNS hostname only}"
: "${ANDROID_PACKAGE_NAME:?Provide ANDROID_PACKAGE_NAME}"
: "${ANDROID_RELEASE_KEYSTORE_PATH:?Release keystore path is missing}"
: "${ANDROID_RELEASE_KEYSTORE_PASSWORD:?Release keystore password is missing}"
: "${ANDROID_RELEASE_KEY_ALIAS:?Release key alias is missing}"

[[ "$API_BASE_URL" == https://* ]] || { echo "API_BASE_URL must use HTTPS." >&2; exit 2; }
[[ "$OIDC_ISSUER" == https://* ]] || { echo "OIDC_ISSUER must use HTTPS." >&2; exit 2; }
[[ "$PASSKEY_RP_ID" =~ ^[A-Za-z0-9.-]+$ ]] || {
  echo "PASSKEY_RP_ID must be a DNS hostname without scheme, port, or path." >&2
  exit 2
}

api_base="${API_BASE_URL%/}"
issuer="${OIDC_ISSUER%/}"
apk="android/app/build/outputs/apk/release/app-release.apk"
aab="android/app/build/outputs/bundle/release/app-release.aab"
tools="$ANDROID_SDK_ROOT/build-tools/36.0.0"
apksigner="$tools/apksigner"
aapt="$tools/aapt"
for required_file in "$apk" "$aab" "$ANDROID_RELEASE_KEYSTORE_PATH" "$apksigner" "$aapt"; do
  [[ -f "$required_file" ]] || { echo "Required release verification file/tool is missing: $required_file" >&2; exit 2; }
done

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

normalize_fingerprint() {
  printf '%s' "$1" | tr -d ':' | tr '[:lower:]' '[:upper:]'
}

expected_fingerprint="$(keytool -list -v \
  -keystore "$ANDROID_RELEASE_KEYSTORE_PATH" \
  -storepass:env ANDROID_RELEASE_KEYSTORE_PASSWORD \
  -alias "$ANDROID_RELEASE_KEY_ALIAS" 2>/dev/null |
  awk -F': ' '/SHA256:/{print $2; exit}')"
[[ -n "$expected_fingerprint" ]] || { echo "Could not read the release certificate fingerprint." >&2; exit 1; }
expected_normalized="$(normalize_fingerprint "$expected_fingerprint")"

"$apksigner" verify --verbose --print-certs "$apk" > "$tmp_dir/apk-signature.txt"
apk_fingerprint="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}' "$tmp_dir/apk-signature.txt")"
[[ -n "$apk_fingerprint" ]] || { echo "APK signature verification did not report a signer." >&2; exit 1; }
[[ "$(normalize_fingerprint "$apk_fingerprint")" == "$expected_normalized" ]] || {
  echo "The signed APK certificate does not match the configured release keystore." >&2
  exit 1
}

jarsigner -verify "$aab" > "$tmp_dir/aab-signature.txt" 2>&1 || {
  echo "The Android App Bundle signature is invalid." >&2
  exit 1
}
aab_fingerprint="$(keytool -printcert -jarfile "$aab" 2>/dev/null |
  awk -F': ' '/SHA256:/{print $2; exit}')"
[[ -n "$aab_fingerprint" ]] || { echo "AAB signature certificate could not be read." >&2; exit 1; }
[[ "$(normalize_fingerprint "$aab_fingerprint")" == "$expected_normalized" ]] || {
  echo "The signed AAB certificate does not match the configured release keystore." >&2
  exit 1
}

package_name="$("$aapt" dump badging "$apk" | grep -m1 "^package:" | cut -d"'" -f2)"
[[ "$package_name" == "$ANDROID_PACKAGE_NAME" ]] || {
  echo "APK package ID does not match ANDROID_PACKAGE_NAME." >&2
  exit 1
}

assetlinks_file="$tmp_dir/assetlinks.json"
assetlinks_status="$(curl --silent --show-error --location --max-time 30 \
  --output "$assetlinks_file" --write-out '%{http_code}|%{content_type}' \
  "https://$PASSKEY_RP_ID/.well-known/assetlinks.json")"
assetlinks_code="${assetlinks_status%%|*}"
assetlinks_type="${assetlinks_status#*|}"
[[ "$assetlinks_code" == "200" ]] || {
  echo "Digital Asset Links must be deployed and return HTTP 200 before a signed release can be uploaded." >&2
  exit 1
}
[[ "$assetlinks_type" == application/json* || "$assetlinks_type" == application/*+json* ]] || {
  echo "Digital Asset Links must be served as application/json." >&2
  exit 1
}
python3 - "$assetlinks_file" "$ANDROID_PACKAGE_NAME" "$expected_fingerprint" <<'PY'
import json
import re
import sys

path, package_name, fingerprint = sys.argv[1:]
normalize = lambda value: re.sub(r"[^0-9a-f]", "", value.lower())
expected = normalize(fingerprint)
with open(path, encoding="utf-8") as stream:
    document = json.load(stream)
if not isinstance(document, list):
    raise SystemExit("Digital Asset Links response must be a JSON array.")
for statement in document:
    target = statement.get("target", {})
    relations = statement.get("relation", [])
    fingerprints = target.get("sha256_cert_fingerprints", [])
    if (
        "delegate_permission/common.get_login_creds" in relations
        and target.get("namespace") == "android_app"
        and target.get("package_name") == package_name
        and any(normalize(value) == expected for value in fingerprints)
    ):
        break
else:
    raise SystemExit("Digital Asset Links does not associate this exact app package and release certificate.")
PY

metadata_file="$tmp_dir/openid-configuration.json"
metadata_code="$(curl --silent --show-error --location --max-time 30 \
  --output "$metadata_file" --write-out '%{http_code}' "$issuer/.well-known/openid-configuration")"
[[ "$metadata_code" == "200" ]] || {
  echo "OIDC discovery did not return HTTP 200 for the configured issuer." >&2
  exit 1
}
python3 - "$metadata_file" "$issuer" <<'PY'
import json
import sys
path, expected_issuer = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    metadata = json.load(stream)
if metadata.get("issuer", "").rstrip("/") != expected_issuer.rstrip("/"):
    raise SystemExit("OIDC discovery issuer does not match the configured issuer.")
for name in ("authorization_endpoint", "token_endpoint", "jwks_uri"):
    value = metadata.get(name, "")
    if not value.startswith("https://"):
        raise SystemExit(f"OIDC discovery is missing a valid HTTPS {name}.")
PY

options_file="$tmp_dir/assertion-options.json"
options_status="$(curl --silent --show-error --location --max-time 30 \
  --request POST --header 'Accept: application/json' \
  --output "$options_file" --write-out '%{http_code}' \
  "$api_base/api/v1/auth/passkeys/assertion/options")"
[[ "$options_status" == "200" ]] || {
  echo "Passkey assertion-options endpoint must be enabled and return HTTP 200 before a signed release can be uploaded." >&2
  exit 1
}
python3 - "$options_file" "$PASSKEY_RP_ID" <<'PY'
import json
import sys
path, expected_rp_id = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    envelope = json.load(stream)
if not envelope.get("challengeId") or not envelope.get("optionsJson"):
    raise SystemExit("Assertion-options response is missing challengeId or optionsJson.")
options = json.loads(envelope["optionsJson"])
if options.get("rpId") != expected_rp_id:
    raise SystemExit("WebAuthn options rpId does not match PASSKEY_RP_ID.")
PY

protected_status="$(curl --silent --show-error --location --max-time 30 \
  --header 'Accept: application/json' --output /dev/null --write-out '%{http_code}' \
  "$api_base/api/v1/me/neighborhood")"
[[ "$protected_status" == "401" || "$protected_status" == "403" ]] || {
  echo "The protected neighborhood API must reject anonymous requests with HTTP 401 or 403." >&2
  exit 1
}

{
  echo "## Signed release preflight"
  echo
  echo "- APK and AAB are signed by the configured release certificate."
  echo "- Digital Asset Links matches the exact app package and release certificate."
  echo "- OIDC discovery matches the configured HTTPS issuer."
  echo "- Passkey assertion options are enabled and return the expected RP ID."
  echo "- The protected neighborhood API rejects anonymous access."
  echo "- This preflight does not fabricate a passkey or claim a successful credential assertion."
  echo "- Final acceptance still requires a physical-device passkey registration, sign-out, passkey sign-in, and a successful authenticated protected API call."
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

echo "Release artifacts and deployed passkey prerequisites verified."
