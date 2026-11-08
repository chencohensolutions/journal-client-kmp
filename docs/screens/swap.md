# Swap with a friend

Settings › Backup › **Swap with a friend** (Nov 8, 2026): each user keeps a copy of their journal on a friend's
journal server, and keeps the friend's on theirs, set up from the phones with no command line. The plan:
[swap-from-phones.md](../plans/swap-from-phones.md); the server: journal-server-go docs/peers.md, "Swaps from the
phones" (1.1.0). A phone sees and changes only its own user's swaps.

## Backup's row

The top card of Backup gets **Swap with a friend ›** under Copies on other disks, also with backup off, once the
server answered `serverInfo.swap` (a server older than 1.1.0 shows no row). Its line: "Noa offers to keep your copy"
while an offer waits, "2 friends", or "Keep your copy at a friend's, and theirs here".

## The Swap page (route `swap`)

"Keep a copy of your journal on a friend's journal server, and theirs on yours…", then a card with **Keep my copy
at a friend's ›** and, when the server keeps friends' copies (`SwapInfo.available`: its admin set `peers_folder`),
**Keep a friend's copy here ›** with the free space on that disk. Then:

- **Offers back** (`swaps.offers`, 10 minutes each): "Noa offers to keep your copy too (300 GB)", the minutes left,
  **Accept** (the passphrase step of joining, below) and **Decline** (`declineSwapOffer`; the copy kept here for
  Noa stays).
- **My copy at friends'** (`swaps.mine`): the friend's name with a lock when encrypted, how the copy is doing (from
  its `DataCopy`: "49,414 of 49,414 files", "Copying…", "Your friend's server can't be reached", "No room left…"),
  "Encrypted · up to 1 TB" or "Plain: your friend can see it · up to 250 GB", and **Remove** (a confirm dialog;
  `removeSwap`: nothing more goes there, the files there stay until the friend removes them).
- **Friends' copies here** (`swaps.kept`): the name, "212 GB of 500 GB used" with a bar, and **Revoke** (a dialog
  with "Also delete their files (212 GB)"; `revokeSwap`). A revoked friend says so in orange, with **Delete files**.
- **Copy passphrase › Show my copy passphrase**, when the user's copy key is from a passphrase or one is kept on
  this phone: route `copy-passphrase`, behind the phone's lock (`PhoneLocked`, Face ID on iOS), the words large with
  Copy, Share and Recovery sheet. Not kept here (a new phone): **Fill in from Google Password Manager** (iCloud
  Keychain brings it back by itself on iOS).
- "Your server can't swap yet: it needs journal-server 1.1.0 or later." on an older server.

The page asks the server every 30 s while it shows, every 3 s while an invite's QR code shows (`SwapController.watch`).

## Keep a friend's copy here (route `swap-here`)

1. The friend's name (refused when the user already has a friend of that name, either way) and how much to keep
   for them: chips 50 GB, 100 GB, 250 GB, 500 GB, 1 TB… up to the free space, and "All 1.8 TB"
   (`SwapQuota.choices`; 500 GB or half the free space by default). **Make the invite** (`swapInvite`).
2. "Have Dana scan this in Journal", the QR code of the link, the code, "Works for 9:41, once" counting down,
   **Share** ("Keep your journal's copy on my server: open this in Journal within 10 minutes." and the link) and
   **Copy link**. Expired: "The code expired. Make a new one while you talk." with **Make a new invite**.
3. When the friend's server joined (its name appears in `swaps.kept`): "Dana's server joined", and when it offered
   a copy back, the offer card with Accept and Decline, then **Done**.

## Keep my copy at a friend's (route `swap-there`; `swap-there|<offer id>` to accept an offer)

1. **Link**: **Scan the QR code** (Google's scanner on Android, the camera on iOS) or paste the link, or a message
   holding it (`parseSwapLink`); the friend's name, filled from the link's `from`. A `journal://swap` link opened
   from outside the app (a friend's message) lands here, filled in.
2. **How your copy is kept**: **Encrypted** (default: "Dana can't see your photos, only their sizes. A passphrase
   opens the copy: lose it and the copy is lost.") or **Plain, I trust this friend**. By the user's copy key
   (`SwapInfo.copyKey`): none yet, the phone suggests six words next; from a passphrase, a field for the same one
   with **Fill in from Google Password Manager** (or the one kept on this phone); a key file, nothing to type.
   With **Keep Dana's copy here too** (when this server keeps friends' copies) and its quota chips, the friend is
   offered a copy here in return (`offerQuota`: one scan, both ways; their phone accepts).
3. **The six words** (new copy key only): six random words of the EFF large list (`Passphrase.suggest`, about 77
   bits, from the platform's secure random source), large and numbered, shown once, with **Save to Google
   Password Manager** (Credential Manager; iCloud Keychain on iOS), **Recovery sheet** (a one-page PDF: the words,
   a QR code of them, the server's id, the friend's name, how to restore; to the share sheet), Share and Copy (kept
   out of the clipboard preview). **I've kept it**.
4. **Two words typed back** ("Word 2", "Word 5"; case and spaces ignored) before **Start the copy**.
5. **How it went**: "Your copy is going to Dana's server", encrypted or plain and the quota, "Dana's phone now offers
   to keep their copy here" when offered back. Or the refusal in the app's words (each `SwapRefusal`) with **Back**
   to the step to fix: the link step for a refused code, an unreachable server, the user's own server, a name
   taken or a friend already swapped with under another name (`ALREADY_SWAPPED`, journal-server 1.1.1); the passphrase step otherwise.

When an encrypted copy started with a passphrase, the phone keeps it (Android: encrypted with an Android Keystore
key; iOS: the synced Keychain item) and, on Android, offers it to Google Password Manager if the user didn't save it
there yet. Never on the journal server or the friend's.

## Code

- Shared: [swap/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/swap/) —
  `SwapController` (state, watch, invite, join, accept, decline, remove, revoke), `HttpSwapApi`
  ([Swaps.graphql](../../sharedLogic/src/commonMain/graphql/Swaps.graphql)), `SwapLink.kt`, `Passphrase.kt`,
  `SwapWords.kt`; tests in `SwapControllerTest`.
- Android: [SwapScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SwapScreen.kt)
  (the screens), `SwapRoutes.kt` (wiring), `SwapPlatform.kt` (QR code, Keystore, Credential Manager, the recovery
  sheet, Share, Copy); previews in `SwapScreenshots.kt` with `SwapLab.kt`'s made-up swaps.
- iOS: [SwapView.swift](../../iosApp/iosApp/SwapView.swift) (`SwapViewModel`, the screens; the iOS forms are
  native `Form`s, so the look differs a little from Android's cards), [SwapPlatform.swift](../../iosApp/iosApp/SwapPlatform.swift)
  (QR code, the `kSecAttrSynchronizable` Keychain item, the recovery sheet, Share, Copy); the scanner is
  `QRCodeScanner.swift`'s and Face ID is `PhoneLocked`'s. A `journal://swap` link opens the join as a sheet over the
  journal (`ContentView.swift`). UI lab: `./scripts/ui-lab-ios.sh swap [link|protect|words|check]`.

## Not built yet

Let a new server take over a friend's copy, and invite back the friends whose copies are here (the plan's "A
friend's server set up again" and "The host set up again"); restoring from the phone.
