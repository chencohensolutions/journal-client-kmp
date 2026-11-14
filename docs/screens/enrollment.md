# Enrollment wizard

Shown while the device has no identity. The server side (codes, certificates, the QR link) is
`docs/authentication.md` in journal-server-go; this page is what the app does. Both platforms.

## Steps

1. **Welcome:** what registering does, and **Get started**.
2. **Connect** (`EnrollmentStep.EnterCode`): the server address and the 8-letter code, or **Scan QR code** with
   **Paste link** under it. A `journal://enroll?…` link (scanned, pasted, or opened from the phone's camera) fills
   both in and checks the code at once.

   **Paste link** (Nov 14, 2026; `EnrollmentController.openPasted`, `findEnrollmentLink`): reads the clipboard only
   when tapped. A link anywhere in it (a chat message with words around it, the line `enroll-code` prints) opens
   exactly as a scan does, fingerprint pinning, other addresses and the DHT included; without a link, a code in the
   text fills in the code; with neither, "Nothing to paste. Copy the enrollment link or code first." and nothing
   changes. A whole link pasted into the address or code field opens the same way. On iOS it is the system's Paste
   button (`PasteButton`), so iOS doesn't ask to allow pasting each time.
3. **Confirm** (`EnrollmentStep.Confirm`): "Register this device?" with the server, the account the code is for and
   when it expires, and the device's name. The server row says "Verified by journal-server-ca", or, after a link that
   said who the server is, "Its key matches server ID T6DNBAM-IJR6WLU-…" (`EnrollmentState.serverId`, its first two
   groups).
4. **Register** (`EnrollmentStep.Registering`): key in secure hardware, certificate request, certificate saved,
   connection test; then **Enrolled**, or **Failed** with what went wrong.

## Models on this phone (Nov 8, 2026, Android only)

After enrolling and [photo access](photo-access.md), one more step (`ModelsOnboardingScreen.kt`; shared logic
`ModelsOnboarding` in `local/ModelsOnboarding.kt`, with `ModelsOnboardingTest`), on the same state and downloads as
Settings › [Models on this phone](settings.md#models-on-this-phone):

- **Get this phone ready**, "The app runs a few models on this phone. They're downloaded once, from where they were published (Hugging Face, Google) or from your server, not with the app."
  ("Asking your server what it offers…" until it answers.)
- A card per model the server offers, with what it does, its size and a bar: **Photo similarity** (11 MB) starts
  downloading as the step shows, whatever the network; **Search by words** ("290 MB model and its 440 MB index") has
  a check box, checked at first: checked, it downloads as On device search's Search by words does, waiting for Wi-Fi
  ("Waiting for Wi-Fi" with **Download now**) while **Only on Wi-Fi** is on; unchecked, it stops and is deleted.
  A failed download has **Try again**.
- **Only on Wi-Fi** (On device search's setting, the one for every download), "Downloads go on in the background.
  Settings › Models on this phone shows them.", and **Continue**, which never waits.
- Shown once, after the device enrolls (`JournalSettings.modelsOnboardingDue`, set by the wizard's end, cleared by
  Continue); skipped without showing when the server offers no model or can't be reached. Devices enrolled before
  Nov 8, 2026 don't see it; their Models page is in Settings.
- Screenshots: [onboarding.png](mockups/models/onboarding.png), [onboarding-hebrew.png](mockups/models/onboarding-hebrew.png).
  iOS not built yet ([ios-todo.md](../ios-todo.md)).

## Enrolling by id (Nov 7, 2026)

The QR link from a server with discovery (journal-server-go PR #2, `enroll-code`) also holds `fingerprint=` (the
sha256 of the server certificate's key), `key=` (its DHT key) and `address=` for each of its addresses
(`parseEnrollmentLink`, `EnrollmentLink`). With a fingerprint:

- Every connection of the enrollment, from the first `enrollmentPreview`, checks the server's key against it instead
  of the apps' CA and the host name: the link's server is put in `JournalSettings.serverDiscovery`, which the mTLS
  clients pin (docs/plans/discovery.md, "Phones"). So a bare IP the server certificate doesn't list works, and no
  host name is needed.
- When the address doesn't answer at all, the link's other addresses are tried (400 ms apart, the first to answer
  wins), then the DHT under `key`; the wizard goes on at the address that answered and shows it.
- After enrolling, what the link said is kept (`JournalController.useEnrolledIdentity`), so the app goes on pinning
  the key and finding the server when its address changes. Leaving the wizard without enrolling puts back what was
  kept before.

A link without a fingerprint (an older server), and an address typed by hand, work as before: the CA and the host
name. Typing a server id by hand isn't offered.

**The server's own CA** (Nov 14, 2026): each new server makes its own CA (journal-server-go docs/install.md, "A new
server, and the CA"). In the connection test after the certificate is saved, the wizard also asks
`serverInfo.caCertificate` (on its own: an older server doesn't know it) and hands it to `useEnrolledIdentity`, which
keeps it in `JournalSettings.serverCa` (or forgets an older server's). From then on the mTLS clients trust the server
by that CA too, at any address. So a server with its own CA is enrolled at by its QR code or its pasted link; a typed code reaches only a
server on the CA built into the app, and the wizard shows the usual connection error for any other.
