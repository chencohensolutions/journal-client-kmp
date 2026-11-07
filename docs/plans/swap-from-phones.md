# Plan: setting up a swap from the phones

Proposed on Nov 7, 2026, at the owner's ask: "pair two servers from the clients". Not built. Builds on the swap
([swap-and-encryption.md](swap-and-encryption.md), journal-server-go docs/peers.md) and discovery
([discovery.md](discovery.md)).

## Decided (Nov 7, 2026)

- **No admin role.** A phone never sees or changes anything of another user: not their devices, copies, peers or
  offers. Setting up a swap is not an admin job: each user sets up their own.
- **Invite codes last 10 minutes and work once**, like enrollment codes and `peer invite` today. A friend far away
  uses the link while the two are talking, or a new one is made.
- **No cap per user.** A user may offer friends any amount up to the free space on the disk of `peers_folder`; the
  phone shows that free space when making an invite. (A cap set at the server was proposed and declined.)
- **Copies are encrypted by default**, with a passphrase the phone asks for (the existing `--from-passphrase` key).
  The phone suggests six random words, shows them once and asks for them typed back (the server needs 24 characters,
  or 4 words and 20); a forgotten passphrase is a lost copy.
  A plain copy, which the friend can read, is an option the user picks for a friend they trust.

## Proposed

- **One scan sets up both directions**, with an accept step on the other phone.

## The problem today

Everything is done at the server's command line, by whoever runs it, for the whole server:

1. On the friend's server: `peer invite --name hen --quota 2TB` (a 10-minute code and the host's id).
2. On ours: `peer add dana <address> <code>.<id> --trusted`.
3. In config.json, a copy `{ "peer": "dana" }` on each user who should have one, and a restart.
4. For a swap, all of it again the other way.

A peer belongs to the server, and config.json decides which users' copies go to it.

## The design

### A peer belongs to a user

- On the **owner's** server, a peer added from hen's phone is hen's: kept in `peers_dir/<user>/<name>/` and used
  only for hen's copy. Peers added at the command line stay as today (the server's, for the users config.json lists).
- On the **host**, an invite made on Dana's phone is Dana's: the peers it lets in keep their files in
  `peers_folder/<dana>/<peer>/<user>/`, and their space counts against the quota Dana gave each. Peers invited at the
  command line stay as today.
- The peer certificate the host issues names the host user it belongs to, so the host can check every
  `/peer/` request against it.

### The phone's side (public schema, each acting on the device's own user only)

- `swapInvite(name, quota)`: makes an invite that belongs to this user (quota at most the free space on the disk of
  `peers_folder`) and answers its link: `journal://swap?code=…&id=…&key=…&address=…` (the code, the server id, the DHT
  key, up to 6 addresses: the same as the enrollment link). The app shows it as a QR code and a link to share.
- `joinSwap(link, passphrase?)`: this user's server pairs with the host (addresses, then the DHT; the id is pinned
  from the first connection), keeps the peer as this user's, and starts this user's copy there, encrypted with a
  key from the passphrase unless the user chose a plain copy. No config edit or restart.
- `swaps`: this user's swaps both ways: my copy at each friend's (how far it is, last compare, reachable), and each
  friend's copy I keep (space used of the quota). With `removeSwap` and `revokeSwap`.
- `serverInfo.swap { available, freeSpace }`, so the apps show "Keep a friend's copy here" only when the server keeps
  peers' copies at all (`peers_folder`), with the free space on that disk.

### One scan, both ways

When hen's server joins Dana's, it offers its own invite in the same exchange (with the quota hen chose for
Dana). Dana's phone then shows "hen offers to keep your copy too (2 TB). Accept?" and her server joins back.
Declining leaves a one-way copy.

### A friend's server set up again

After a friend loses their PC, their new server can't reach their copy: the host lets in only the old server's
certificate. The passphrase alone decrypts the copy (it carries the Argon2id salt), so the host only has to let the
new server in. On the host's phone, each friend's copy I keep has **Let a new server take over**: it makes an invite
for that copy (10 minutes, once), the friend's new server joins with it and gets the same files, and the old
certificate is refused from then on. Then `restore --passphrase` (from the friend's phone later). Nobody can read
the copy without the passphrase; a forgotten one is a lost copy.

### In the apps

- Settings > Backup gets **Swap with a friend**: "Keep a friend's copy here" (quota, then the QR code and Share) and
  "Keep my copy at a friend's" (Scan, or paste a link; then the passphrase, or "plain, I trust this friend").
- The friends appear under the copies as today (`kind: PEER`), and in a Swaps list with the space and Remove.

### What stays at the server

Whoever runs it decides whether the server keeps friends' copies (`peers_folder`), and where. The copy keys never leave the server or the phone that made them; restore stays at the server.

## Limits

- At least one of the two servers must be reachable from outside (the router's port opened by the server, or
  IPv6). Behind carrier-grade NAT on both sides they can't connect: there is no relay.
- Both servers need the discovery build.

## Order of work

1. Server: peers that belong to a user (both sides), the four calls and `serverInfo.swap`; tests.
2. Apps: the Swap screens (Android, then iOS on the Mac), the QR code and the scan.
3. One scan both ways.
