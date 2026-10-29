# Plan: swap between servers, encrypted copies, restore, encrypted accounts

**Parts 1a, 1, 2 and 3 built** (Oct 29, 2026: journal-server-go branch `copies-target`, docs/peers.md; the app's
Copies page on Android, with the lock for an encrypted copy). Parts 4–6 not built yet. Restore needs --to (never the data folder by default). Added on Oct 29 at the owner's ask: copy keys from a passphrase (Argon2id; `copy-key new --from-passphrase`, `restore --passphrase`) and previous versions of replaced sidecars in every copy (`history/`, `restore --as-of`). Built differently from below:
the manifest at the peer is sealed with a key from the naming key (so this server can read it back), not with age;
a server set up again is re-invited under its old name instead of `peer adopt`. Agreed with the owner on Oct 28–29, 2026 on Windows. It covers the server (journal-server-go) and
the apps. Today a user's data folder can be copied only to other folders ([../../../journal-server-go/docs/copies.md](../../../journal-server-go/docs/copies.md)):
another disk, a USB disk or a network share. This plan adds copies on a friend's server over the internet, optionally
encrypted, a way to restore from them, and accounts the server itself can't read.

## Three switches, all off by default

| Switch | Who sets it | Where | Default |
|---|---|---|---|
| **Encrypted account** | The user, at onboarding | On the phone; fixed for the account (moving an account over is the future plan) | Off: standard |
| **Swap** | The server's admin | `peer add` once per friend's server, then a copy to that peer per user | Off: no peers |
| **Encrypted copy** | The server's admin | `"encrypt": true` on a copy to a peer | Off: plain, and only to a peer marked trusted |

| Account | Copy to a peer | Copy encrypted | Result |
|---|---|---|---|
| Standard | no | – | Today: copies on other disks only |
| Standard | yes | no | A plain copy at a friend's server the owner marked trusted |
| Standard | yes | yes | A copy the friend can't read; restoring needs the owner's key file |
| Encrypted | no | – | The server can't read the user's data |
| Encrypted | yes | – (always ciphertext) | Neither server can read it |

Each part ships on its own. An encrypted copy needs swap; swap of an encrypted account needs both swap and encrypted
accounts.

## Why

- **Off site.** Copies on other disks stay in the same house as the server. A copy at a friend's house survives a
  fire, a theft or a break-in.
- **Swap.** The friend runs journal-server too, so each server keeps the other's copy. No cloud account, no fees.
- **The friend shouldn't have to see the photos.** Hence the encrypted copy for standard accounts, and encrypted
  accounts, so the owner can also let friends keep data on this server without being able to read it (hosting
  friends' accounts is the future plan; encrypted accounts are its base).

## Decisions (and why)

- **A copy to a peer is a copy, like the ones on other disks.** `copies` in config.json gets a `peer` in place of a
  `folder`. It keeps everything copies have: the daily pass at `at`, `realtime`, `copy` / `copy report`, `startCopy` /
  `stopCopy`, the progress and the app's Settings › Backup › Copies screen. The copier works on a `Target` (a folder
  or a peer) instead of the file system directly.
- **The primary pushes; the peer only stores.** The primary already decides what a copy lacks, when to copy, and
  shows the progress; pushing reuses all of that. The peer's server is reachable on the internet anyway (its own
  phones reach it). Restore pulls back over the same connection, so only the primary ever connects out.
- **A peer doesn't serve phones**, make previews or build indexes. It keeps files and gives them back. Serving the
  owner's phones from the peer while the primary is off is the future plan; the peer keeps the data folder's layout and
  names users `user@server`, so that can be added without moving any data.
- **Plaintext goes only to a trusted peer.** A plain copy needs the peer added with `--trusted`; the server refuses
  config.json otherwise. The warning is shown once, when the peer is marked trusted.
- **The key that can decrypt never stays on a server.** An encrypted copy is made with a public key; the private key
  goes to a file the owner keeps. An encrypted account's key lives only on the user's phones and in the user's own
  backup (a key file or a passphrase).
- **Nothing is deleted from a peer copy**, as for copies on disks (with the same JPEG XL exception, below).
- **Restore is part of this plan**: a backup that can't be brought back isn't one.

## Part 1: swap for standard accounts

### Peering

```bash
# on the friend's server (the host): one code per server it agrees to keep copies for
journal-server peer invite --name hen-home --quota 2TB        # prints a code, valid 10 minutes, single use

# on the owner's server
journal-server peer add dana https://dana.example:56398 CODE [--trusted]
journal-server peers                                          # both directions: who keeps ours, whose we keep
journal-server peer remove dana                               # stops copying there; the files at dana stay
```

- `peer add` enrolls this server with the host like a device enrolls: it makes a key, sends a certificate request and
  gets a client certificate signed by the host's client CA, with a **peer** role and `CN=<name from the invite>`. It
  keeps it under `certs/peers/<name>/`. It trusts the host's server certificate the way phones do at enrollment.
- On the host, `peers.json` (beside `devices.json`) lists each peer: name, serial, quota, used bytes, added, revoked.
  `peer revoke <name>` refuses its certificate; the files stay until the host's admin deletes them.
- A peer certificate reaches only the peer API, and only its own space. A device certificate can't reach the peer API.
- A swap is two peerings, one each way.

### Config

```json
{ "username": "hen", "data_folder": "D:\\Journal\\hen",
  "copies": [
    { "folder": "E:\\Journal copy\\hen", "realtime": true },
    { "peer": "dana", "at": "02:00" },
    { "peer": "dana", "encrypt": true, "realtime": true }
  ] }
```

(The two `dana` lines are alternatives, shown together; a user has at most one copy per peer.)

### The peer API (on the host)

Over mTLS with the peer certificate, under `/peer/`. The host keeps each peer's files in
`peers_folder/<peer>/<user>/` (`peers_folder` in config.json; the host's admin chooses its disk).

- `GET /peer/{user}/list?prefix=media/2026/` — paths, sizes and modified times, streamed. One call replaces the
  thousands of `os.Stat`s a disk copy makes; a pass lists each year once.
- `PUT /peer/{user}/files/{path}` — resumable, like `PUT /upload/{sha256}` (`Content-Range`, `Upload-Offset`), with the
  file's sha256. The host writes to a temp file, checks the sha256 and renames into place, so a copy never holds part
  of a file.
- `GET /peer/{user}/files/{path}` with `Range` (restore), and `GET …/sha256` (the host hashes its own file).
- `DELETE /peer/{user}/files/{path}` — only for the JPEG XL rule below.
- `GET /peer/space` — quota, used and free bytes. Over quota, `PUT` answers `QUOTA_FULL` and the pass stops, as a full
  disk stops it.

### What changes on the primary

- `copies.Target`: list, stat, put (atomic, with sha256 and progress), get, delete, free space, ready. `folderTarget`
  is today's code; `peerTarget` is the API above. **The refactor ships first, with no change in behaviour**, guarded by
  `copies/copies_test.go`.
- Problems: `PEER_UNREACHABLE` (like `FOLDER_MISSING`: from the first failure until one succeeds) and `QUOTA_FULL`
  (like `DISK_FULL`). A network error retries the file a few times with backoff before counting it as failed.
- Optional `"bandwidth_kbps"` per copy, so a pass doesn't fill the home upload.
- The compare (`CompareNow`) uses the listing. `.journal-copy.json` (the last pass and compare) is written to the peer
  too.
- **JPEG XL on a peer.** A disk copy deletes a JPEG the data folder now keeps as JPEG XL only after decoding the
  copy's JPEG XL and matching bytes. Over the internet that means downloading both. On a peer: once the peer has the
  JPEG XL, the copier asks the peer for the JPEG's sha256 (`GET …/sha256`, hashed on the host) and deletes it when it is
  the one the JPEG XL's `encodedFrom` names. The decode check already ran on the primary when the JPEG XL was made.

### In the apps

Settings › Backup › Copies lists a peer copy like a disk copy, with the peer's name instead of a folder, and a lock
when it is encrypted. `copies` in the public schema gets `kind` (`FOLDER` / `PEER`), `encrypted`, and the new
problems; quota left replaces free space. No new screen.

## Part 2: encrypted copies (standard accounts)

```bash
journal-server copy-key new hen --out E:\hen-copy.journalkey   # once per user; refuses to overwrite
```

- Makes an **age X25519 key pair** and a random **naming key**. The primary keeps the public key and the naming key in
  `certs/copy-keys/hen.json`; the key file (private key + naming key + the user's name and date) is written where the
  owner says, then the command asks the owner to type the last 6 characters of the private key shown on screen, to
  make sure it was saved. `--passphrase` protects the file itself (age's scrypt). The server forgets the private key.
- Without the key file, the encrypted copy can't be read, by anyone. The command says so, and `copy report` says
  where the key file was written.
- **Each file is encrypted with age on its own**, streaming, to the public key. Anyone with the key file can decrypt a
  file with the stock `age` tool.
- **Names.** Each path becomes `HMAC-SHA256(namingKey, lower-case path)` in hex, under `blobs/<2 hex>/`, so the friend
  sees neither names nor dates.
- **The manifest** lists, per path: the opaque name, the source's size and sha256, the source's modified time, and the
  encrypted size. The primary keeps it under the server's own folder (not the data folder, which copies only read),
  writes it encrypted to the peer after each pass (`manifest.age`, plus the previous one), and rebuilds it from the
  peer's manifest when its own is lost. What a copy lacks is worked out from it: no listing, no network.
- A changed sidecar is uploaded again under the same name; the original is never re-sent (originals don't change).
- **What the friend sees**: how many files, their sizes (rounded by age's 64 KiB chunks, not hidden) and when they
  changed. Not content, names, dates or places.

## Part 3: restore

```bash
journal-server restore hen --from dana [--key E:\hen-copy.journalkey] [--to D:\Journal\hen]
```

- Into an **empty** folder only (the user's `data_folder` by default); it refuses any other.
- Plain copy: lists and downloads each file into the same path. Encrypted copy: reads `manifest.age` with the key file,
  downloads each blob and decrypts it to its path. Encrypted account (Part 6): the blobs come back as they are.
- Each original is checked against its sha256 after it is written (the sidecar's, or the manifest's). A failed file is
  retried, then listed at the end.
- It goes on where it stopped when run again: a file already there with the right size and sha256 is skipped.
- The server needn't be running; restore runs on its own, like `index`. Indexes and previews rebuild afterwards, as
  they do when `.cache/` is missing.
- **When the whole PC is lost**: install journal-server, restore `certs/` from a backup if there is one (the old peer
  identity then still works); otherwise ask the friend for a new invite and run `peer add` again. A new peering can
  read the files the old one wrote for the same user name once the host's admin runs
  `journal-server peer adopt <new> <old>` (so a stranger with an invite can't).

## Part 4: encrypted accounts

### Choosing it

- The admin creates the user in config.json as today. The **first device to enroll** chooses Standard or Encrypted
  in onboarding, after the certificate. The server records it (`users/<name>/account.json`) and it can't change while
  the account has files. A user with files already is standard (moving one over is the future plan).
- Onboarding says plainly: the server can't read your photos, can't make previews or search them, and **if you lose
  every phone and your key, your photos are gone**.

### The key

The user's **master key** (256-bit) is either:
- **From a passphrase**: Argon2id (parameters fixed in the format spec; strong enough for a phone to take about a
  second). The server keeps the salt and a key check (a short value encrypted with the key) so a wrong passphrase is
  told at once. Since someone with the server's disk could try passphrases offline, onboarding asks for a long one
  (suggests 6 random words) and rates it.
- **Generated**: random. Onboarding doesn't finish until the **key file** is saved through the system's file
  manager (Android: `ACTION_CREATE_DOCUMENT`; iOS: `fileExporter`), to a USB disk, Drive, iCloud, a computer, wherever
  the user likes; optionally protected with a passphrase. The app then asks the user to open the file once
  (`ACTION_OPEN_DOCUMENT` / `fileImporter`) to prove it can be read back. The server keeps only the key check.

On the phone the master key is kept wrapped by the Android Keystore / iOS Keychain (device only, not synced). Nothing
on the server can decrypt. **Another phone**: enroll its certificate as today, then type the passphrase or open the
key file. From the master key, separate keys are derived with HKDF: `idKey`, `metaKey`, `nameKey`, `indexKey`.

**Private files** get a **private key** of their own, random, stored on the server wrapped by the master key *and* by
a passphrase or key file of its own if the user wants some phones not to have it. A phone without it can't open private
items, so "withhold private files from this device" holds without trusting the server. (Whether to ask for this at
onboarding or only when the first file is marked private: decide with the mockups.)

### The format (a spec in the server's docs before any code)

- **Item id** = `HMAC-SHA256(idKey, the original's bytes)`, in place of the sha256, so the server can't check whether
  a user has some known photo. The same photo from two phones is still one item.
- **Item key**: random per item. Every blob of the item (original, previews, poster) is encrypted with it.
- **Blobs**: chunked AEAD, 64 KiB chunks (XChaCha20-Poly1305, or AES-256-GCM where the platform has only that; chosen
  in the spec), each chunk numbered and the last marked, so nothing can be cut, reordered or swapped between files. A
  byte range of the plain file maps to whole chunks, so **video seeks with `Range`** as today.
- **Meta**: one encrypted JSON per item, what a sidecar holds today (dates, place, camera, source, marks such as
  hidden), plus the item key and the original's real sha256. Encrypted with `metaKey`, or with the private key for a
  private item. Marking an item private rewrites only its meta.
- **Account blobs**: preview profiles, the wrapped private key, `people` (names of faces), each encrypted and
  versioned.

### On the server

A user with `encrypted` keeps `blobs/` in the data folder instead of `media/`:

- `PUT /blob/{name}?kind=original|meta|preview|poster|index|account` — resumable as uploads are today, checked
  against the ciphertext's sha256; `If-None-Match: *` for write-once blobs, `If-Match: <version>` for meta and account
  blobs (a stale version answers `412`; the phone merges and tries again).
- `GET /blob/{name}` with `Range`. `POST /blob/{name}/verify` as today, on the ciphertext.
- The change feed lists name, kind, size, version and arrival order; nothing else is known.
- Previews count against a size limit like `preview_cache_max_gb` and the oldest unread go first (the server knows
  the kind, not what's inside). Originals, meta and index blobs are never evicted.
- Off for an encrypted user: previews, posters, every index, nudity, JPEG XL, imports, private withholding (done by
  keys instead), `searchMedia`, `people`, `similarFaces`. `serverInfo` says the account is encrypted, so the apps
  hide what depends on them.
- Copies to disks and to peers copy `blobs/` like any files; they need no encrypted copy (Part 2) on top.

### In the app

- **Upload**: hash with `idKey`, make the item key, encrypt to a temp file, upload, verify, then write the meta. The
  backup ledger keeps the id beside the sha256. The phone that uploads also makes its own preview sizes and a video
  poster while it has the original.
- **Previews on request** (the owner's choice): every phone asks for the exact size it needs, as today. The blob's
  name is `HMAC(nameKey, id + size + fit + format + quality)`, so the phone knows it without asking and the server
  can't learn sizes or shapes. On `404` the phone downloads the original, decrypts it, makes the preview, encrypts and
  uploads it, and shows it. Two phones making the same one at once is harmless (`If-None-Match`).
  - While it makes one, the tile shows the nearest size the phone knows exists (its own other sizes, or one from
    another phone's profile, read from the account blobs), scaled.
  - Phones with different screens make their own sizes; no phone makes previews for the whole library.
- **Viewing**: originals and videos are decrypted as they stream; nothing decrypted is written outside the app's
  cache.
- The library database stores what the meta says, so Moments, Explore and the map work as today, from the phone.

## Part 5: indexing on the phone (encrypted accounts)

- In Settings › Search, an encrypted account turns indexes on per phone, as "On this phone" does today: text search
  and faces first; text in photos, similar photos and aesthetic scores after.
- **One phone indexes at a time**: on charger and Wi-Fi it takes a lease from the server for the next items
  (`indexLease(index)`: a range of the feed, an hour long), so phones don't do the same work twice.
- It reads each item **from a preview** of the size the model needs (about 512 px for SigLIP, about 1080 px for faces;
  made on request as above), not the original.
- It **uploads the vectors encrypted** (`kind=index`, one blob per batch, with `indexKey`; private items' vectors in
  their own blobs, under the private key), and **keeps them decrypted in its local index** (`localindex/`, as today).
  Other phones download the batches and decrypt them into their own local index; search runs on the phone, offline
  too.
- Faces are grouped on the phone that indexes, and the names and groups go to the `people` account blob (versioned).
- Models come from the server as today (`/models/…`); the vision half of the search model is needed now, not only the
  text half.

## Part 6: swap and restore for encrypted accounts

A copy of an encrypted account to a peer is a plain copy of `blobs/`: the friend sees ciphertext, so the peer needn't
be trusted and `"encrypt"` isn't asked for. Restore brings `blobs/` back; the phones still have the key.

## Order

| # | Part | Needs | Delivers |
|---|---|---|---|
| 1a | `copies.Target` refactor | – | Nothing visible; the base for peers |
| 1 | Swap for standard accounts: peering, peer API, peer copies, report, app status | 1a | An off-site copy at a trusted friend's |
| 2 | Encrypted copies: key file, opaque names, manifest | 1 | An off-site copy the friend can't read |
| 3 | Restore, plain and encrypted | 1, 2 | Getting it back |
| 4 | Encrypted accounts: spec, blob store, onboarding and key, upload, viewing, previews on request, private key | – | An account the server can't read |
| 5 | Indexing on the phone | 4 | Search and faces for encrypted accounts |
| 6 | Swap and restore for encrypted accounts | 1, 3, 4 | Their off-site copy |

1–3 and 4–5 are independent; 1–3 first, since they protect the owner's existing library.

## To decide while building

- The exact Argon2id parameters, the cipher, and the key file's format (in the spec, Part 4).
- How a phone without the private key shows a private item: not at all, or a locked tile.
- Whether peers' certificates expire with the same 365 days as devices', and how they renew (devices don't yet).
- What the host shows its admin about the peers it keeps copies for (`journal-server peers` is the start).

## Future plan (not in this plan)

- **Serving from a peer while the primary is off**: the peer accepts the user's phones (the primary's client CA,
  scoped to the user; `devices.json` copied), `serverInfo` lists peers with their TLS fingerprints, the phone switches
  to the peer and shows "Read-only: your server is off", previews made on the peer for standard accounts.
- Moving an existing account to encrypted (the server does it once with the key, also making previews and encrypted
  vectors from what it has).
- Hosting friends' accounts on this server (encrypted accounts plus quotas per user).
- An export tool that decrypts an encrypted account into the plain data-folder layout.
- Search on a peer.
