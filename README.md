# Journal client (Kotlin Multiplatform)

Android and iOS clients for journal-server-go. Only the logic is shared; each platform has a native UI.

| Module | Contents |
|---|---|
| [sharedLogic](./sharedLogic/src) | Shared Kotlin: settings, connection state (`JournalController`), the device photo library (`LibraryController`), GraphQL via Apollo Kotlin, bundled server CA. Platform folders hold the mTLS client and the photo library (MediaStore / PhotoKit) for each OS. |
| [androidApp](./androidApp/src/main/kotlin) | Jetpack Compose UI. |
| [iosApp](./iosApp/iosApp) | SwiftUI UI, using the `SharedLogic` framework. |

## Design

What the app is, the decisions behind it, the data format and the current status: [docs/design.md](./docs/design.md).

## Screens

Each screen after enrollment (photo access, the Journal home, All photos, the viewer) is described in
[docs/screens](./docs/screens/README.md).

## Authentication (mTLS)

The server authenticates users by client certificate; the certificate CN maps to the user.

**Register this device.** Without an identity the app opens the enrollment wizard, which asks for the server
address and a one-time code from `journal-server enroll-code <user>`. It creates an EC P-256 key in secure hardware
(Android Keystore/StrongBox, iOS Secure Enclave; software keys on emulators where neither is available), sends a
certificate request, and stores the certificate the server issues. After that the app opens on the home screen.
The logic is shared ([enrollment/](./sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/enrollment/));
see `docs/authentication.md` in journal-server-go for the full design.

The server CA is embedded in
[server-ca.pem](./sharedLogic/src/commonMain/certs/server-ca.pem) (compiled into the app at build time). If the CA
is regenerated, run `./gradlew :sharedLogic:syncServerCa` to copy `certs/ca.pem` from journal-server-go, and ship a
new build. The GraphQL endpoint is `/query`. To reach a server on your computer from the Android emulator, run
`adb reverse tcp:8080 tcp:8080` and use `localhost:8080` (the server certificate must list the address you use).

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
