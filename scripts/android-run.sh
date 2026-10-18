#!/usr/bin/env bash
# Build the debug app, install it on one device and open it.
#   ./scripts/android-run.sh phone      first real device (USB or wireless debugging)
#   ./scripts/android-run.sh emulator   first running emulator
#   ./scripts/android-run.sh <serial>   a specific device from `adb devices`
# Installing over an existing copy keeps its data (stories, tags, enrollment).
set -euo pipefail

target="${1:-phone}"
cd "$(dirname "$0")/.."

sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
# Gradle needs a JDK: JAVA_HOME, else Homebrew's openjdk@21, else the one bundled with Android Studio.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for jdk in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
               "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [[ -x "$jdk/bin/java" ]]; then export JAVA_HOME="$jdk"; break; fi
    done
fi

devices=$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
case "$target" in
    phone) serial=$(grep -v '^emulator-' <<<"$devices" | head -1 || true) ;;
    emulator) serial=$(grep '^emulator-' <<<"$devices" | head -1 || true) ;;
    *) serial=$(grep -Fx "$target" <<<"$devices" || true) ;;
esac
if [[ -z "$serial" ]]; then
    echo "No $target connected. Connected devices:" >&2
    "$adb" devices -l >&2
    exit 1
fi
echo "Device: $serial ($("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r'))"

./gradlew --console=plain :androidApp:assembleDebug
"$adb" -s "$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
"$adb" -s "$serial" shell am start -S -n com.chencohensolution.journal/.MainActivity # -S: stop the old process so the new build runs
echo "Running on $serial"
