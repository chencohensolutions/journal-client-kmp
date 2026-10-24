#!/usr/bin/env bash
# Build the UI Lab app (the debug build under its own id, com.chencohensolution.journal.uilab, so it sits beside
# Journal), install it on the connected phone (else the first running emulator) and open it: the app's screens and
# suggested designs for them, with made-up data. See docs/previews.md.
#   ./scripts/ui-lab.sh                    the list of screens
#   ./scripts/ui-lab.sh moments            a screen's options: the current one first, then the suggestions
#   ./scripts/ui-lab.sh moments current    one option (ids in LabScreens.kt)
#   DEVICE=emulator ./scripts/ui-lab.sh    the emulator even when a phone is connected (or DEVICE=<serial>)
set -euo pipefail

screen="${1:-}"
option="${2:-}"
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
case "${DEVICE:-}" in
    "") serial=$(grep -v '^emulator-' <<<"$devices" | head -1 || true)
        [[ -n "$serial" ]] || serial=$(grep '^emulator-' <<<"$devices" | head -1 || true) ;;
    phone) serial=$(grep -v '^emulator-' <<<"$devices" | head -1 || true) ;;
    emulator) serial=$(grep '^emulator-' <<<"$devices" | head -1 || true) ;;
    *) serial=$(grep -Fx "$DEVICE" <<<"$devices" || true) ;;
esac
if [[ -z "$serial" ]]; then
    echo "No phone or emulator found. Connected devices:" >&2
    "$adb" devices -l >&2
    exit 1
fi

echo "Device: $serial ($("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r'))"
# Only the device's processor's native libraries (as android-run.sh's ABI=): about a third of the APK, which over
# wireless debugging is minutes less of installing.
abi=$("$adb" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
./gradlew --console=plain -Pabi="$abi" :androidApp:assembleUiLab
"$adb" -s "$serial" install -r androidApp/build/outputs/apk/uiLab/androidApp-uiLab.apk
# -S: stop the old process so the new build runs; --user current: without it, a phone with a Secure Folder (a second
# user) refuses -S
"$adb" -s "$serial" shell am start --user current -S -n com.chencohensolution.journal.uilab/com.chencohensolution.journal.ui.library.UiLabActivity ${screen:+--es screen "$screen"} ${option:+--es option "$option"}
