# Plan: servers and phones find each other without a fixed address

Proposed on Nov 7, 2026, at the owner's ask: "Syncthing resolves devices from their ids; add that so swap servers
find each other when they have no static IP address or host name". **Decided the same day:** discovery through the
**BitTorrent DHT** (free, no server to run), and **no relay**. **Built for servers** (journal-server-go branch
`claude/project-thread-f40fq0`, docs/peers.md "Finding the host"): server ids, peers telling each other their
addresses, the DHT. Not built: port mapping, and everything on the phones (parts 2 and 3 below).

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

- `peer add dana <address> <code>.<id>`: the address is only the first guess (`peer invite` prints the host's public
  IP when it has no `public_url`). `peer.json` gains `addresses` (the one that last worked first) beside `url`,
  which stays as the owner typed it.
- The server works out its own addresses at start and whenever the network changes: its LAN IPs (IPv4 and global
  IPv6), the port mapping from step 4, the public IP the peers and discovery see, and `public_url` if set.
- **Peers tell each other.** A new peer route, `GET /peer/addresses` (with the peer certificate), answers the
  host's addresses, its DHT key and the caller's IP; the owner's server asks at start and every 30 minutes. In a
  swap both servers ask both ways. (A `POST` that pushes a move at once was left out: the DHT covers it.)
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

### 5. The BitTorrent DHT as the phone book (chosen)

- Each server publishes a BEP 44 mutable item: under an ed25519 key made from its TLS key, signed with it, and sealed
  with a key made from its fingerprint, so DHT nodes see an opaque blob. Every 45 minutes and when its addresses
  change. Peers get the DHT key when they enroll and from `GET /peer/addresses`.
- A server that can't reach a peer at any known address looks it up there and dials what it finds.
- **Phones (not built):** a small DHT client in `sharedLogic` (KRPC over UDP: `get` toward the target
  SHA1(key + salt), from the BitTorrent bootstrap nodes; check the ed25519 signature; open the seal), used only when
  the last address and Bonjour fail. The phone gets the DHT key at enrollment (a new `serverInfo` field or the
  enrollment answer). Salt `journal-server/v1`; the record format is in journal-server-go `discovery/record.go`.
- **No relay** (the owner's choice): a server behind carrier-grade NAT is found but can't be reached.

## Order of work

1. **Server id and many addresses for peers** (server only; built): the id format, `GET /peer/addresses`, dialing all addresses, docs/peers.md. Fixes a swap where one side's IP changes.
2. **Port mapping and IPv6** (server only).
3. **Phones**: pin the id instead of the host name, `serverInfo.addresses`, trying them in order; enrollment by id.
   Then Bonjour on the LAN. Android first if built on Windows (docs/ios-todo.md).
4. **DHT**: built for servers (part 5); the phones' DHT client after part 3.

## Decided

- Nov 7, 2026: the BitTorrent DHT for discovery; no relay; no paid or self-run rendezvous server.
