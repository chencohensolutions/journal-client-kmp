# Journal client (Kotlin Multiplatform)

Android and iOS clients for journal-server-go. Only the logic is shared; each platform has a native UI.

| Module | Contents |
|---|---|
| [sharedLogic](./sharedLogic/src) | Shared Kotlin: settings, connection state (`JournalController`), GraphQL probe (Ktor), bundled server CA. Platform folders hold the mTLS client for each OS. |
| [androidApp](./androidApp/src/main/kotlin) | Jetpack Compose UI. |
| [iosApp](./iosApp/iosApp) | SwiftUI UI, using the `SharedLogic` framework. |

## Authentication (mTLS)

The server authenticates users by client certificate; the certificate CN maps to the user.

- **Android:** install the user's `.p12` in the system (Settings → Security → Encryption & credentials →
  Install a certificate → VPN & app user certificate), then tap **Select identity** to pick it from the KeyChain.
- **iOS:** tap **Import Identity (.p12)…** and pick the bundle produced by
  `scripts/generate-user-cert.ps1` in journal-server-go (no password by default). It is stored in the app Keychain.

The server CA is embedded in
[ServerCa.kt](./sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/tls/ServerCa.kt); replace it if
the CA is regenerated. From the Android emulator, the host machine is `https://10.0.2.2:8080/graphql`.

## Running

- Android: `./gradlew :androidApp:assembleDebug`, or run `androidApp` from Android Studio.
- iOS: open [iosApp/iosApp.xcodeproj](./iosApp/iosApp.xcodeproj) in Xcode and run. The build phase compiles
  `sharedLogic` via Gradle.

## Tests

The shared tests in `sharedLogic/src/commonTest` run on both platforms:

- `./gradlew :sharedLogic:testAndroidHostTest`
- `./gradlew :sharedLogic:iosSimulatorArm64Test`
