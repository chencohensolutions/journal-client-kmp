# Enrollment wizard

Shown while the device has no identity. The server side (codes, certificates, the QR link) is
`docs/authentication.md` in journal-server-go; this page is what the app does. Both platforms.

## Steps

1. **Welcome:** what registering does, and **Get started**.
2. **Connect** (`EnrollmentStep.EnterCode`): the server address and the 8-letter code, or **Scan QR code**. A
   `journal://enroll?…` link (scanned, or opened from the phone's camera) fills both in and checks the code at once.
3. **Confirm** (`EnrollmentStep.Confirm`): "Register this device?" with the server, the account the code is for and
   when it expires, and the device's name. The server row says "Verified by journal-server-ca", or, after a link that
   said who the server is, "Its key matches server ID T6DNBAM-IJR6WLU-…" (`EnrollmentState.serverId`, its first two
   groups).
4. **Register** (`EnrollmentStep.Registering`): key in secure hardware, certificate request, certificate saved,
   connection test; then **Enrolled**, or **Failed** with what went wrong.

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
