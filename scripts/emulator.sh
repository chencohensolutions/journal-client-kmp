#!/usr/bin/env bash
# Start or stop this project's Android emulator, so it runs only while needed.
#   ./scripts/emulator.sh up       start it (headless, on the Mac's GPU) and wait until Android has booted
#   ./scripts/emulator.sh down     shut it down; the quick-boot snapshot is saved, so the next `up` takes seconds
#   ./scripts/emulator.sh status   print its serial if running
# `up` first shuts down any other running emulator (scripts and adb pick the first one); KEEP_OTHERS=1 skips that.
# WINDOW=1 shows the emulator window. AVD=<name> uses another emulator.
set -euo pipefail

avd="${AVD:-Journal}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"

emulators() { "$adb" devices | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1 }'; }
avd_of() { "$adb" -s "$1" emu avd name 2>/dev/null | head -1 | tr -d '\r'; }
serial_of_avd() {
    local s
    for s in $(emulators); do [[ "$(avd_of "$s")" == "$1" ]] && { echo "$s"; return; }; done
    return 0
}
stop() {
    "$adb" -s "$1" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 60); do emulators | grep -qx "$1" || return 0; sleep 1; done
    echo "$1 did not shut down" >&2
}

case "${1:-}" in
    up)
        serial=$(serial_of_avd "$avd")
        if [[ -z "${KEEP_OTHERS:-}" ]]; then
            for s in $(emulators); do
                [[ "$s" == "$serial" ]] && continue
                echo "Shutting down $s ($(avd_of "$s"))"
                stop "$s"
            done
        fi
        if [[ -z "$serial" ]]; then
            echo "Starting $avd"
            args=(-avd "$avd" -gpu host -no-audio -no-boot-anim)
            [[ -n "${WINDOW:-}" ]] || args+=(-no-window)
            nohup "$emulator" "${args[@]}" >"${TMPDIR:-/tmp}/emulator-$avd.log" 2>&1 &
            for _ in $(seq 60); do serial=$(serial_of_avd "$avd"); [[ -n "$serial" ]] && break; sleep 2; done
            [[ -n "$serial" ]] || { echo "$avd did not start, see ${TMPDIR:-/tmp}/emulator-$avd.log" >&2; exit 1; }
        fi
        for _ in $(seq 90); do
            [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
            sleep 2
        done
        [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] ||
            { echo "$avd did not finish booting" >&2; exit 1; }
        echo "$avd ready: $serial"
        ;;
    down)
        serial=$(serial_of_avd "$avd")
        if [[ -z "$serial" ]]; then echo "$avd is not running"; exit 0; fi
        stop "$serial"
        echo "$avd stopped"
        ;;
    status)
        serial=$(serial_of_avd "$avd")
        if [[ -n "$serial" ]]; then echo "$avd running: $serial"; else echo "$avd is not running"; fi
        ;;
    *) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
