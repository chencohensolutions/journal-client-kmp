# Releasing the Android app

GitHub builds and publishes the app (`.github/workflows/android-release.yml`) by itself: every push to main releases
the version in the `VERSION` file at the root, once. To release, raise it and merge:

```sh
scripts/bump-version.sh          # VERSION becomes today's date, or today's next .N (2026.11.14.2)
git commit -am "Version 2026.11.14.2" && git push   # through a pull request, as usual
```

A push to main whose VERSION already has a release does nothing. `git push origin <commit>:release/2026.11.14.3`
releases that version from any commit instead. Local builds read VERSION too (versionName and versionCode), unless
`-PversionName`/`-PversionCode` are given. Binaries go only on the release, never into git (the 2026.11.14 APK in
`releases/` was the last).

GitHub Actions then runs the shared tests, builds the arm64 release APK (R8, compressed native libraries, about
33 MB) signed with the release key, and publishes it as `journal-android.apk` on the GitHub Release `v<version>`, with
its sha256 beside it. It takes about 15 minutes; follow it in the repository's Actions tab. The newest APK is always
at <https://github.com/chencohensolutions/journal-client-kmp/releases/latest/download/journal-android.apk>.

- The branch name is the version. The app and the server share one scheme since Nov 14, 2026: the day it was
  made, `YYYY.MM.DD`, with `.2`, `.3`, ... for a second or third version that day (`release/2026.11.14.2`). It
  becomes `versionName` (shown at the bottom of Settings, under Your server, beside the server's own version), and
  `versionCode` is the date's digits times 100 plus that number (2026.11.14 is 2026111400, 2026.11.14.2 is
  2026111402). A phone installs an APK over the one it has only when the versionCode is higher, so each release
  needs a later date or number. Which app works with which server: `releases/README.md`.
- Pushing the same release branch again rebuilds it and replaces that release's APK. Phones that already installed
  that version won't see it as an update; push a new version for that.
- Only arm64 phones (every phone of the last years). Emulators and old 32-bit phones use a debug build.

## The release key (once)

Every published APK must be signed with the same key, or a phone refuses to install it over the one it has. Keep
the keystore and its password somewhere safe (a password manager): losing it means users have to uninstall the app
(and lose its data on the phone) to install the next one. On any machine with a JDK:

```sh
keytool -genkeypair -v -keystore journal-release.jks -alias journal -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Journal"
base64 -w0 journal-release.jks > journal-release.jks.base64   # macOS: base64 -i journal-release.jks -o journal-release.jks.base64
```

On Windows PowerShell the second line is
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("journal-release.jks")) > journal-release.jks.base64`.

Then in the repository's Settings › Secrets and variables › Actions, add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the contents of `journal-release.jks.base64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password keytool asked for |
| `ANDROID_KEY_ALIAS` | `journal` |
| `ANDROID_KEY_PASSWORD` | only if you gave the key a different password |

Without them the workflow stops at "Release key" and publishes nothing.

The release APK is signed with another key than debug and `profile` builds, so the first time, uninstall the
debug-signed app on a phone before installing a release.

## Locally

`./gradlew :androidApp:assembleRelease -Pabi=arm64-v8a -PversionName=2026.11.14 -PversionCode=2026111400` builds the same APK when
`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS` are set; without them the release
build is unsigned. The `profile` build stays debug-signed for measuring on a phone.
