#!/usr/bin/env bash
# Reinstall and run WebDriverAgent on the booted iOS simulator, so mobile-mcp can tap/swipe/read the UI.
# Stays in the foreground while WDA serves on http://localhost:8100; stop it with Ctrl+C.
set -euo pipefail

WDA_VERSION="v16.15.3"
WDA_DIR="$HOME/Developer/WebDriverAgent"

udid=$(xcrun simctl list devices booted | grep -m1 -oE '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}' || true)
if [[ -z "$udid" ]]; then
    echo "No booted iOS simulator. Boot one (open -a Simulator) and run again." >&2
    exit 1
fi
echo "Simulator: $udid"

if [[ ! -d "$WDA_DIR" ]]; then
    git clone --depth 1 --branch "$WDA_VERSION" https://github.com/appium/WebDriverAgent.git "$WDA_DIR"
fi

# Replace any WDA already running or installed on the simulator.
pkill -f "xcodebuild.*WebDriverAgentRunner" || true
xcrun simctl uninstall "$udid" com.facebook.WebDriverAgentRunner.xctrunner || true

cd "$WDA_DIR"
xcodebuild build-for-testing \
    -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner \
    -destination "id=$udid" -derivedDataPath build -quiet

echo "Starting WebDriverAgent..."
exec xcodebuild test-without-building \
    -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner \
    -destination "id=$udid" -derivedDataPath build
