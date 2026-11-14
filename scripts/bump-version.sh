#!/bin/sh
# Sets VERSION to today's date and stages it for the next commit (docs/install.md "Versions").
#
# usage: scripts/bump-version.sh [<version>]   (default: today, such as 2026.11.14; 2026.11.14.2 if today's is taken)
#
# The app and the server share this scheme: a version is the day it was built, YYYY.MM.DD, with .2, .3, ... for a
# second or third version on the same day. A push to main that changes VERSION publishes that version on the GitHub
# Releases page (.github/workflows/android-release.yml, docs/releases.md), so run this when the app is ready to release.
set -eu
cd "$(dirname "$0")/.."
file=VERSION
current=$(tr -d ' \r\n' < "$file")
case "${1:-}" in
    "")
        today=$(date +%Y.%m.%d)
        case "$current" in
            "$today") next="$today.2" ;;
            "$today".*) next="$today.$(( ${current##*.} + 1 ))" ;;
            *) next="$today" ;;
        esac
        ;;
    [0-9]*.[0-9]*.[0-9]*) next="$1" ;;
    *) sed -n '4p' "$0" | sed 's/^# //' >&2; exit 2 ;;
esac
printf '%s\n' "$next" > "$file"
git add "$file"
echo "version $current -> $next"
