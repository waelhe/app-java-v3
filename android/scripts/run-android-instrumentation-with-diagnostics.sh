#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd -- "${script_dir}/../.." && pwd)"
android_dir="${workspace_root}/android"
diagnostics_dir="${android_dir}/android-ci-diagnostics"
mkdir -p "${diagnostics_dir}"

capture_android_diagnostics() {
  local exit_code=$?
  trap - EXIT
  set +e

  printf '%s\n' "${exit_code}" > "${diagnostics_dir}/exit-status.txt"
  {
    echo "Captured at (UTC): $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "Gradle command: :app:connectedDebugAndroidTest"
    echo "Emulator API requested: 36"
    echo "Workspace root: ${workspace_root}"
  } > "${diagnostics_dir}/environment.txt"

  timeout 30s adb devices -l > "${diagnostics_dir}/adb-devices.txt" 2>&1
  for property in ro.build.version.sdk ro.build.fingerprint sys.boot_completed init.svc.bootanim; do
    {
      printf '\n[%s]\n' "${property}"
      timeout 10s adb shell getprop "${property}"
    } >> "${diagnostics_dir}/device-properties.txt" 2>&1
  done
  timeout 30s adb shell pm list instrumentation > "${diagnostics_dir}/instrumentation-packages.txt" 2>&1
  timeout 30s adb shell dumpsys activity instrumentation > "${diagnostics_dir}/activity-instrumentation.txt" 2>&1
  timeout 60s adb logcat -d -b all -v threadtime > "${diagnostics_dir}/logcat-all.txt" 2>&1

  mkdir -p "${diagnostics_dir}/test-reports"
  local report_path report_name
  for report_path in \
    app/build/reports/androidTests/connected \
    app/build/outputs/androidTest-results/connected \
    app/build/reports/tests/connectedDebugAndroidTest; do
    if [ -e "${android_dir}/${report_path}" ]; then
      report_name="${report_path//\//_}"
      rm -rf "${diagnostics_dir}/test-reports/${report_name}"
      cp -R "${android_dir}/${report_path}" "${diagnostics_dir}/test-reports/${report_name}"
    fi
  done

  if [ -s "${diagnostics_dir}/logcat-all.txt" ]; then
    grep -Ein 'FATAL EXCEPTION|AndroidRuntime|INSTRUMENTATION_FAILED|Process: .*\(pid|ANR in|am_crash|am_anr|Exception|AssertionError' \
      "${diagnostics_dir}/logcat-all.txt" > "${diagnostics_dir}/logcat-suspects.txt" || true
  fi
  exit "${exit_code}"
}

trap capture_android_diagnostics EXIT

gradle_status=0
if (
  cd "${android_dir}"
  ./gradlew :app:connectedDebugAndroidTest \
    -PoidcClientId=android-community-native \
    -PoidcRedirectUri=com.marketplace.android:/oauth2redirect \
    --stacktrace --console=plain
) > "${diagnostics_dir}/connectedDebugAndroidTest.log" 2>&1; then
  gradle_status=0
else
  gradle_status=$?
fi

cat "${diagnostics_dir}/connectedDebugAndroidTest.log"
exit "${gradle_status}"
