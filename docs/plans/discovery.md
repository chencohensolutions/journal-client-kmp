# Plan: servers and phones find each other without a fixed address

**Not built yet.** Proposed on Nov 7, 2026, at the owner's ask: "Syncthing resolves devices from their ids; add that
so swap servers find each other when they have no static IP address or host name". Waiting for the owner's choices
("Choices for the owner" below) before building.

## The problem today

- A peer is added with an address: `peer add dana https://dana.example:56398 <code>.<fingerprint>`. The address is
  kept in `peers_dir/dana/peer.json` and never changes. When dana's home IP changes, or dana has no host name, the
  copies to dana stop (`PEER_UNREACHABLE`) until someone runs `peer add` again by hand.
- A phone is enrolled with an address too (`JournalSettings.serverUrl`), and the server certificate must list that
  address (`generate-server-cert.sh`), so a phone can't follow the server to a new IP either.
- Both work today only with a dynamic-DNS name and a port forwarded by hand on the router.

## How Syncthing does it

Syncthing never needs an address from the user. Each part below is a layer; a device tries all the addresses it
learns, and keeps the best connection it gets.

1. **The id is the key.** Each device makes its own self-signed TLS certificate once. Its **device id** is the
   SHA-256 of that certificate, written in base32 with a Luhn check letter every 13 letters, in 8 groups of 7
   (`MFZWI3D-BONSGYC-…`). There is no authority: a connection is accepted when the other side's certificate hashes
   to the id expected, whatever address it came from. So an address is only a hint, and a wrong or stale address
   can't let anyone pretend to be the device.
2. **Local discovery.** On the home network, every 30 seconds each device sends a small packet (UDP port 21027: IPv4
   broadcast, IPv6 multicast `ff12::8384`) with its id and its addresses. Devices on the same LAN find each other
   with no internet at all.
3. **Global discovery.** A discovery server (`discovery.syncthing.net`, or one you run, `stdiscosrv`) is a phone
   book of id → addresses. A device **announces** every 30 minutes over HTTPS with its own certificate, so the server
   knows the id from the TLS handshake and nobody can announce for another id. Addresses like `tcp://:22000` with
   no IP are filled in by the server from the address the announce came from: that is how a device behind a router
   learns its public IP. A **lookup** (`GET /v2/?device=<id>`) is anonymous and returns the addresses. The client
   pins the discovery server by its own id too, so a fake discovery server can only give wrong hints, which the
   id check in step 1 refuses.
4. **Opening the router.** Each device asks the router to forward its port (UPnP IGD, NAT-PMP), renews the mapping,
   and announces the outside address it got.
5. **Hole punching.** Over QUIC (UDP), a device learns its outside UDP address with STUN, announces it, and two
   devices behind ordinary home routers dial each other at once so both routers let the packets in.
6. **Relays.** When nothing else works (carrier-grade NAT, strict firewalls), each device keeps a connection open
   to a public relay and announces `relay://…`. The other device asks the relay for it; the relay then just pipes
   bytes. The TLS session runs end to end inside, so the relay can't read anything. Relays are slow, so Syncthing
   keeps trying for a direct connection and moves to it when one works.

## What we already have

- **Peers already trust by key, not by name.** An invite carries the host's fingerprint (the SHA-256 of its server
  key, `peers.Fingerprint`) and `copies.PeerClient` checks it on every connection. That is step 1 already: only the
  address is fixed. Phones don't do it yet: they check the apps' CA and the host name.
- Every connection is mTLS, so an announce can be authenticated the way Syncthing's is, with the certificate the
  server already has.

## The design

### 1. A server id

- The **server id** is the fingerprint we already use, written as Syncthing writes ids: base32, check letters, groups
  of 7 (`journal-server id` prints it; `setup` prints it once). Invites use it in place of the hex fingerprint, and
  the old hex form keeps working.
- It is the key's fingerprint, not the certificate's, so renewing the server certificate keeps the id. Only a new
  key changes it (as today: a new key needs a new invite).

### 2. Addresses, not an address

- `peer add dana <code>.<id>`: the address becomes optional. `peer.json` gains `addresses` (a list, newest first,
  each with when it last worked) beside `url`, which stays as the address the owner typed, tried first.
- The server works out its own addresses at start and whenever the network changes: its LAN IPs (IPv4 and global
  IPv6), the port mapping from step 4, the public IP the peers and discovery see, and `public_url` if set.
- **Peers tell each other.** A new peer route, `POST /peer/addresses` (with the peer certificate), sends this
  server's current addresses to the host, at start, every 30 minutes, and at once when they change. Each server also
  answers `GET /peer/addresses` with its own list. In a swap both servers do this both ways, so when one moves, it
  tells the other at the old address the other still knows. This needs no outside service.
- `copies.Peer` dials every known address (the last that worked first; the others a moment later, as browsers do),
  checking the id on each, and keeps the first that answers.
- **Phones** get the list too: `serverInfo.addresses` (public schema), kept by the app beside `serverUrl` and tried
  in the same order. The app pins the server id it saw at enrollment and checks it instead of the host name, so an
  IP address works without a new certificate. The enrollment screen accepts an id (with an optional address to
  start from) or a QR code from `journal-server enroll-code`.

### 3. On the home network: Bonjour (mDNS), not Syncthing's broadcast

- The server announces `_journal._tcp` on the LAN with its id in the TXT record (a pure-Go responder, no cgo).
- Phones find it with the system's own Bonjour (`NsdManager` on Android, `NWBrowser` on iOS). Syncthing's UDP
  broadcast needs the multicast entitlement on iOS, which Apple grants only on request; Bonjour needs only
  `NSBonjourServices` and the local-network prompt.
- A phone at home then reaches the server directly, even when the public IP changed, and a phone on Wi-Fi
  needn't go out to the internet and back.

### 4. Opening the router by itself

- The server asks the router for a port mapping (UPnP IGD and NAT-PMP/PCP, pure Go), renews it every 30 minutes,
  and removes it on a clean stop. `"port_mapping": false` turns it off. The outside address it gets joins the list.
- Global IPv6 needs no mapping, only the Windows firewall rule the installer already adds; many home and mobile
  lines now have IPv6, so this alone often works.

### 5. A rendezvous service (discovery and relay), optional

For the cases the peers can't solve alone: both servers moved at once, a server behind carrier-grade NAT, or a phone
that has been away longer than its addresses last.

- `journal-server rendezvous` runs a small discovery server: announce over mTLS with the server key (the id is
  taken from the handshake), lookup by id, entries kept for an hour. It needs a machine with a fixed address: a
  small VPS, or a friend's server that has one. Config: `"rendezvous": [{"url": "...", "id": "..."}]`, pinned by id
  like a peer. Lookups return only addresses, never names or users.
- The same command can relay (step 6 of Syncthing): the server keeps one connection open to it, a dialer asks for
  the id, and the relay pipes the bytes. The mTLS session is end to end, so the relay sees only how much passes.
  Copies are throttled through a relay (`bandwidth_kbps`), and phones use it for the feed and previews but wait for
  a direct connection to upload originals.
- QUIC hole punching is left out: the phones' HTTP clients (OkHttp, URLSession) speak TCP, and the relay covers the
  rest. It can come later between servers only.

## Order of work

1. **Server id and many addresses for peers** (server only): the id format, optional address in `peer add`,
   `POST/GET /peer/addresses`, dialing all addresses, docs/peers.md. Fixes a swap where one side's IP changes.
2. **Port mapping and IPv6** (server only).
3. **Phones**: pin the id instead of the host name, `serverInfo.addresses`, trying them in order; enrollment by id.
   Then Bonjour on the LAN. Android first if built on Windows (docs/ios-todo.md).
4. **Rendezvous**: discovery, then relay.

## Choices for the owner

- **Who runs the rendezvous service.** (a) Nobody for now: parts 1–3 only, enough while the two servers in a swap
  don't both move at once and neither is behind carrier-grade NAT. (b) The owner runs `journal-server rendezvous`
  on a small VPS, and the community's servers can point at it or run their own. (c) Syncthing's public discovery
  servers speak a documented protocol and would accept our announces, but they are donated for Syncthing users;
  not recommended.
- Whether phones should go through a relay at all, or only servers.
- Whether the server id should replace the apps' CA for phones entirely (simpler: each phone trusts one key), or sit
  beside it.
