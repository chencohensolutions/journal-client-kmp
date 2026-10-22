#!/usr/bin/env bash
# Build the debug app, install it on a booted iOS simulator and open the UI lab (debug builds only): the app's screens
# and suggested designs for them, with made-up data. The iOS twin of ui-lab.sh; the ids are the same. See docs/previews.md.
#   ./scripts/ui-lab-ios.sh                    the list of screens
#   ./scripts/ui-lab-ios.sh moments            a screen's options: the current one first, then the suggestions
#   ./scripts/ui-lab-ios.sh moments current    one option (ids in iosApp/iosApp/Lab/LabScreens.swift)
# SIMULATOR=<name or udid> picks the simulator; otherwise "iPhone 18 Pro" if booted, else the first booted one.
set -euo pipefail

screen="${1:-}"
option="${2:-}"
cd "$(dirname "$0")/.."

booted=$(xcrun simctl list devices booted)
uuid='[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}'
wanted="${SIMULATOR:-iPhone 18 Pro}"
udid=$(grep -F "$wanted" <<<"$booted" | grep -m1 -oE "$uuid" || true)
[[ -z "$udid" && -z "${SIMULATOR:-}" ]] && udid=$(grep -m1 -oE "$uuid" <<<"$booted" || true)
if [[ -z "$udid" ]]; then
    echo "No booted simulator${SIMULATOR:+ matching $SIMULATOR}. Boot one (open -a Simulator) and run again." >&2
    exit 1
fi

derived=build/ios-lab
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -destination "id=$udid" -derivedDataPath "$derived" -quiet build
app="$derived/Build/Products/Debug-iphonesimulator/Journal.app"
bundle=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")

xcrun simctl install "$udid" "$app"
# Launch arguments become UserDefaults for this run only; the app reads them in UiLab.swift.
xcrun simctl launch --terminate-running-process "$udid" "$bundle" \
    -uiLab YES ${screen:+-labScreen "$screen"} ${option:+-labOption "$option"} >/dev/null
open -a Simulator
