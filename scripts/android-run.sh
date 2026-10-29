#!/usr/bin/env bash
# Build the debug app, install it on one device and open it.
#   ./scripts/android-run.sh phone      first real device (USB or wireless debugging)
#   ./scripts/android-run.sh emulator   first running emulator
#   ./scripts/android-run.sh <serial>   a specific device from `adb devices`
# ABI=arm64-v8a packs only that processor's native libraries (a smaller APK, quicker to install over wireless
# debugging); otherwise the APK has every ABI.
# Installing over an existing copy keeps its data (stories, tags, enrollment).
set -euo pipefail
# Git Bash on Windows would rewrite the device's paths (/data/local/tmp) into Windows ones.
export MSYS_NO_PATHCONV=1

target="${1:-phone}"
cd "$(dirname "$0")/.."

sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[[ -d "$sdk" ]] || sdk="${LOCALAPPDATA:-}/Android/Sdk" # Windows
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

./gradlew --console=plain :androidApp:assembleDebug ${ABI:+-Pabi=$ABI}
apk=androidApp/build/outputs/apk/debug/androidApp-debug.apk
# Pushed compressed (zstd), then installed from the device: a debug APK is mostly stored uncompressed (code and native
# libraries), and `adb install` streams it as is, so over wireless debugging this takes about half as long (a 102 MB APK:
# 21 s → 12 s on the owner's phone).
remote=/data/local/tmp/journal-debug.apk
install() {
    "$adb" -s "$serial" push -z zstd "$apk" "$remote" && "$adb" -s "$serial" shell pm install -r -t "$remote"
}
# The first install over wireless debugging often fails while the connection wakes up; the retry succeeds.
install || { echo "Install failed, retrying once..." >&2; sleep 2; install; }
"$adb" -s "$serial" shell rm -f "$remote"
"$adb" -s "$serial" shell am start -S -n com.chencohensolution.journal/.MainActivity # -S: stop the old process so the new build runs
echo "Running on $serial"
