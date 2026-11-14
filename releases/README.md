# Releases kept in the repository

From 2026.11.14.2 on, releases are on the repository's Releases page only (published by
`.github/workflows/android-release.yml`), not in git. The file below is the last one kept here.

`android/journal-android-<version>.apk` is the Android app (arm64 phones), signed with the release key and kept in
git LFS. Download it from the file's page on GitHub ("Download raw file") and open it on the phone. The first time,
uninstall a debug build of the app first: it is signed with another key.

The Windows server installer is in the server's repository, `journal-server-go/releases/windows/`.

## Versions

The app and the server share one scheme since Nov 14, 2026: a version is the day it was made, `YYYY.MM.DD`, with
`.2`, `.3`, ... for a second or third one the same day (docs/releases.md). The app shows its version and the
server's at the bottom of Settings; the server prints its own when it starts.

An app and a server work together when the server's API number (`serverInfo.api`) is one the app knows. A newer
server keeps answering older apps; with an older server the app leaves out what that server can't do yet (it reads
it from `serverInfo`).

| App | versionCode | Needs server API | Works with servers |
|---|---|---|---|
| next build | | 1 | every server; keeps a server's own CA from 2026.11.14.2 (`serverInfo.caCertificate`) |
| 2026.11.14 | 2026111400 | 1 | 1.2.8 and newer, 2026.11.14 and newer |

Servers from 2026.11.14.2 make their own CA when they are set up. Every app enrolls there by the QR code (it pins the
server's key); builds after 2026.11.14 also keep the CA, so the server stays trusted when its certificate is made
again with a new key.

sha256 of journal-android-2026.11.14.apk: 7bc84e896c6b5066581664017c8811ceec896c94502cf07e8fa37bd16825e35a
