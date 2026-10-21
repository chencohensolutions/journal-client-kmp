#!/usr/bin/env bash
# Build the debug app, install it on the first running emulator and open the UI lab (debug builds only): the app's
# screens and suggested designs for them, with made-up data. See docs/previews.md.
#   ./scripts/ui-lab.sh                    the list of screens
#   ./scripts/ui-lab.sh moments            a screen's options: the current one first, then the suggestions
#   ./scripts/ui-lab.sh moments current    one option (ids in LabScreens.kt)
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

serial=$("$adb" devices | awk 'NR > 1 && $2 == "device" && /^emulator-/ { print $1; exit }')
if [[ -z "$serial" ]]; then
    echo "No emulator running. Connected devices:" >&2
    "$adb" devices -l >&2
    exit 1
fi

./gradlew --console=plain :androidApp:assembleDebug
"$adb" -s "$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# -S: stop the old process so the new build runs
"$adb" -s "$serial" shell am start -S -n com.chencohensolution.journal/.ui.library.UiLabActivity ${screen:+--es screen "$screen"} ${option:+--es option "$option"}
