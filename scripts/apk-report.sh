#!/usr/bin/env bash
# Summarise a built APK without a device: size, SDK levels, permissions, native libraries
# and the biggest contents. Usage: scripts/apk-report.sh [path/to/app.apk]
# Needs the Android build-tools (aapt2) and unzip; ANDROID_HOME or ANDROID_SDK_ROOT must be set.
set -euo pipefail

# A normal build writes to outputs/; a single-ABI build (-Pandroid.injected.build.abi=...) to intermediates/.
APK="${1:-$(ls -t androidApp/build/outputs/apk/debug/*arm64-v8a*.apk androidApp/build/intermediates/apk/debug/*arm64-v8a*.apk 2>/dev/null | head -1 || true)}"
[ -n "${APK:-}" ] && [ -f "$APK" ] || { echo "No APK found. Build first: ./gradlew :androidApp:assembleDebug" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -x "$AAPT2" ] || { echo "aapt2 not found under $SDK/build-tools" >&2; exit 1; }

echo "== $APK"
ls -lh "$APK" | awk '{print "size:", $5}'
"$AAPT2" dump badging "$APK" 2>/dev/null | grep -E "^(package|sdkVersion|targetSdkVersion|application-label:)" | sed 's/^/  /'

echo; echo "== permissions"
"$AAPT2" dump permissions "$APK" 2>/dev/null | sed 's/^/  /'

echo; echo "== native libraries per ABI (count, MB uncompressed)"
unzip -l "$APK" 'lib/*' 2>/dev/null | awk '$4 ~ /^lib\// {split($4,p,"/"); n[p[2]]++; s[p[2]]+=$1} END {for (a in n) printf "  %-12s %4d files %8.1f MB\n", a, n[a], s[a]/1048576}'

echo; echo "== our own native pieces"
unzip -l "$APK" 'lib/*' 2>/dev/null | awk '$4 ~ /(kodrix_exec|native-lib|libnode_bin|libgit_bin)/ {printf "  %-45s %8.1f MB\n", $4, $1/1048576}'

echo; echo "== assets"
unzip -l "$APK" 'assets/*' 2>/dev/null | awk '$4 ~ /^assets\// {split($4,p,"/"); d=p[2]; if (d ~ /\./) d="(root files)"; n[d]++; s[d]+=$1} END {for (d in n) printf "  %-22s %5d files %8.1f MB\n", d, n[d], s[d]/1048576}' | sort -k4 -n -r

echo; echo "== 12 biggest entries"
unzip -l "$APK" 2>/dev/null | awk 'NR>3 && $1 ~ /^[0-9]+$/ {print $1, $4}' | sort -n -r | head -12 | awk '{printf "  %8.1f MB  %s\n", $1/1048576, $2}'
