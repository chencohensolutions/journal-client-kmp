#!/usr/bin/env bash
# Render the Android screen previews to PNGs, without a device or Android Studio. See docs/previews.md.
#   ./scripts/screenshots.sh           render every @PreviewTest preview
#   ./scripts/screenshots.sh check     compare against the committed images instead (fails on differences)
set -euo pipefail
cd "$(dirname "$0")/.."

# Gradle needs a JDK: JAVA_HOME, else Homebrew's openjdk@21, else the one bundled with Android Studio.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for jdk in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
               "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [[ -x "$jdk/bin/java" ]]; then export JAVA_HOME="$jdk"; break; fi
    done
fi

if [[ "${1:-}" == "check" ]]; then
    ./gradlew :androidApp:validateDebugScreenshotTest
else
    ./gradlew :androidApp:updateDebugScreenshotTest
    echo "Images: androidApp/src/screenshotTestDebug/reference/"
fi
