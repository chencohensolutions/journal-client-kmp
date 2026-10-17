# Journal client (Kotlin Multiplatform)

Android and iOS clients for journal-server-go. Only the logic is shared; each platform has a native UI.

| Module | Contents |
|---|---|
| [sharedLogic](./sharedLogic/src) | Shared Kotlin: settings, connection state (`JournalController`), GraphQL via Apollo Kotlin, bundled server CA. Platform folders hold the mTLS client for each OS. |
| [androidApp](./androidApp/src/main/kotlin) | Jetpack Compose UI. |
| [iosApp](./iosApp/iosApp) | SwiftUI UI, using the `SharedLogic` framework. |

## Authentication (mTLS)

The server authenticates users by client certificate; the certificate CN maps to the user.

**Register this device (recommended).** On first launch, or via **Register this device**, the
enrollment wizard asks for the server address and a one-time code from `journal-server enroll-code <user>`.
It creates an EC P-256 key in secure hardware (Android Keystore/StrongBox, iOS Secure Enclave; software keys on
emulators where neither is available), sends a certificate request, and stores the certificate the server issues.
The logic is shared ([enrollment/](./sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/enrollment/));
see `docs/authentication.md` in journal-server-go for the full design.

Alternatively, use a certificate file:

- **Android:** install the user's `.p12` in the system (Settings → Security → Encryption & credentials →
  Install a certificate → VPN & app user certificate), then tap **Select identity** to pick it from the KeyChain.
- **iOS:** tap **Import Identity (.p12)…** and pick the bundle produced by
  `scripts/generate-user-cert.ps1` in journal-server-go (no password by default). It is stored in the app Keychain.

The server CA is embedded in
[ServerCa.kt](./sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/tls/ServerCa.kt); replace it if
the CA is regenerated. The GraphQL endpoint is `/query`; from the Android emulator the host machine is `https://10.0.2.2:8080/query`.

## GraphQL (Apollo Kotlin)

- Schema: [schema.graphqls](./sharedLogic/src/commonMain/graphql/schema.graphqls), copied from
  `journal-server-go/graph/schema/schema.graphql`. When the server schema changes, run
  `./gradlew :sharedLogic:syncServerSchema` (add `-PjournalServerDir=<path>` if the server repo isn't a sibling
  folder) and commit the result. Don't edit the copy by hand.
- Operations: add `.graphql` files next to it (e.g. [Images.graphql](./sharedLogic/src/commonMain/graphql/Images.graphql)).
  Apollo generates typed `…Query` classes in `com.chencohensolution.journal.graphql` on build
  (or `./gradlew :sharedLogic:generateApolloSources`).
- Apollo runs on the platform mTLS Ktor client (`apollo-engine-ktor`), so requests use the selected identity.
- Generated models are `internal`; map them to plain models (like `JournalImage`) before exposing them to the UIs.

## Running

- Android: `./gradlew :androidApp:assembleDebug`, or run `androidApp` from Android Studio.
- iOS: open [iosApp/iosApp.xcodeproj](./iosApp/iosApp.xcodeproj) in Xcode and run. The build phase compiles
  `sharedLogic` via Gradle.

## Tests

The shared tests in `sharedLogic/src/commonTest` run on both platforms:

- `./gradlew :sharedLogic:testAndroidHostTest`
- `./gradlew :sharedLogic:iosSimulatorArm64Test`
