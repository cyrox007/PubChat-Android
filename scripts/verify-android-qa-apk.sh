#!/usr/bin/env bash
# Verify the artifact is actually signed with an Android APK v2+ compatible
# certificate. Print only public SHA-256 fingerprints, never passwords/keys.
set -euo pipefail
APK="${1:?Usage: verify-android-qa-apk.sh path/to/app.apk [cert-fingerprint-output]}"
OUTPUT="${2:-}"
if [[ ! -s "$APK" ]]; then
  echo "::error::APK is missing or empty: $APK" >&2
  exit 1
fi

APKSIGNER="$(command -v apksigner || true)"
if [[ -z "$APKSIGNER" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    APKSIGNER="$(find "$SDK_ROOT/build-tools" -maxdepth 2 -name apksigner | sort -V | tail -n 1)"
  fi
fi
if [[ -z "$APKSIGNER" || ! -x "$APKSIGNER" ]]; then
  echo "::error::Android build-tools/apksigner executable was not found" >&2
  exit 1
fi

if ! DETAILS="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)"; then
  echo "::error::apksigner rejected QA APK signature" >&2
  printf '%s\n' "$DETAILS" >&2
  exit 1
fi

FINGERPRINT="$(printf '%s\n' "$DETAILS" | sed -nE 's/^.*certificate SHA-256 digest: ([0-9a-fA-F]+).*$/\1/p' | head -n 1)"
if [[ -z "$FINGERPRINT" ]]; then
  echo "::error::apksigner passed but did not report a SHA-256 signing certificate" >&2
  printf '%s\n' "$DETAILS" >&2
  exit 1
fi

echo "Signing certificate SHA-256: $FINGERPRINT"
if [[ -n "$OUTPUT" ]]; then
  printf 'Signing certificate SHA-256: %s\n' "$FINGERPRINT" > "$OUTPUT"
fi
