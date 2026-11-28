#!/usr/bin/env bash
# Desktop update patches in the GitHub builds (see UpdateTool.java and Boot.java).
#   ci.sh cert         before building: builds the signing key's certificate into the app
#   ci.sh make <os>    after building: build/update/roarstream-update-<os>.zip + .properties
# Uses the Android signing secrets (ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS,
# ANDROID_KEY_PASSWORD); without them it does nothing and that build has no patches.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ -z "${ANDROID_KEYSTORE_BASE64:-}" ]; then echo "No signing key: no update patch"; exit 0; fi
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
tmp="${RUNNER_TEMP:-/tmp}"
if command -v cygpath > /dev/null; then tmp=$(cygpath -u "$tmp"); fi
ks="$tmp/update-key.jks"
[ -f "$ks" ] || echo "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$ks"
case "$1" in
  cert)
    pem="$tmp/roarstream-update.pem"
    java packaging/update/UpdateTool.java cert "$(native "$ks")" "$ANDROID_KEYSTORE_PASSWORD" "$ANDROID_KEY_ALIAS" "$(native "$pem")"
    echo "ROARSTREAM_UPDATE_CERT=$(native "$pem")" >> "$GITHUB_ENV"
    ;;
  make)
    version=$(grep -o 'VERSION = "[^"]*"' app/src/main/kotlin/com/lagradost/cloudstream3/linux/ui/AppWindow.kt | cut -d'"' -f2)
    java packaging/update/UpdateTool.java make app/build/install/RoarStream/lib build/update "$2" "$version" \
      "${GITHUB_SHA:0:12}" "$(native "$ks")" "$ANDROID_KEYSTORE_PASSWORD" "$ANDROID_KEY_ALIAS" "$ANDROID_KEY_PASSWORD"
    ls -la build/update
    ;;
esac
