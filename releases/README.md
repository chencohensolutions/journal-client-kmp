# Releases kept in the repository

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
| 2026.11.14 | 2026111400 | 1 | 1.2.8 and newer, 2026.11.14 and newer |
