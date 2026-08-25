# Slipstream — design

**Date:** 2026-08-25
**Status:** Approved, ready for implementation planning
**Scope:** Two applications (Android + Windows) implementing peer-to-peer file transfer,
browsing, and media streaming over the local network only.

---

## 1. Purpose

Move files and stream media between one Android phone and one Windows PC over whatever local
network they happen to share, with zero configuration after a one-time pairing, at the maximum
speed the radio allows, and without any traffic ever leaving the local network.

Two network scenarios drive the design, in order of frequency:

1. **Phone hotspot** — the PC is a DHCP client of the phone's softAP. The phone is the PC's
   default gateway.
2. **External WiFi** — both devices are clients of a third-party AP whose behaviour is unknown
   (may drop multicast, may enforce client isolation).

### Goals

- Discovery and reconnection in ~1s on either network, with no user action.
- Saturate the available link on bulk transfers.
- Real streaming (seekable, nothing written to disk) initiated from **either** device.
- Every operation invocable from either end: push and pull, browse and be browsed.
- Resumable transfers that survive a network switch.

### Non-goals

- Multi-peer / many-device meshes. Exactly two paired devices.
- Internet relay, remote access, or NAT traversal of any kind.
- Play Store distribution (see §2).
- iOS, macOS, or Linux.
- Continuous background folder sync. Transfers are explicit.

---

## 2. Constraints and decisions taken

| Decision | Choice | Rationale |
|---|---|---|
| Distribution | Personal, sideloaded APK | Unlocks `MANAGE_EXTERNAL_STORAGE` for full filesystem browsing; avoids Play policy review, which rejects that permission for non-file-manager apps |
| Android stack | Kotlin + Jetpack Compose + Media3 | Chosen deliberately over reusing VanSale's XML `:meridian-ui` module; requires a Compose port of the design system (§13) |
| Windows stack | C# / .NET 9 + WinUI 3 | Native throughput, modern Fluent shell, straightforward tray + autostart |
| Trust | Pair once, then fully silent | Permanent cert-fingerprint trust; unpaired devices ignored entirely, so untrusted networks stay safe with no per-transfer friction |
| PC playback | Hand off to system default player | Fire-and-forget. No embedded player, no remote control, no position sync |
| Code sharing | None — shared **specification**, two implementations | The protocol is small; a shared native core was judged not worth the JNI cost |
| Localisation | English only, LTR | Personal tool. VanSale's bilingual/RTL-first requirement does not apply |
| Dark mode | Both platforms, from day one | Media app used at night; VanSale's light-only web rule does not apply |

---

## 3. Architecture

Both applications implement the same six modules. There is no client/server asymmetry — each
device runs a complete peer. This is what makes "either end can initiate any operation" a
property of the architecture rather than a feature to be added.

| Module | Responsibility |
|---|---|
| **Discovery** | Locate the paired peer on the current network |
| **Identity** | Device ID, self-signed certificate, fingerprint, pairing state |
| **Control channel** | Persistent TLS connection; request/response + server-push events |
| **Transfer engine** | Bulk byte movement; parallel, resumable, verified |
| **Media server** | HTTP/1.1 with `Range` support; streams and thumbnails |
| **UI** | Compose (Android) / WinUI 3 (Windows), both wearing Meridian |

### Ports

| Port | Proto | Purpose | Encrypted |
|---|---|---|---|
| 53320 | UDP | Discovery announce/query, multicast `224.0.0.167` | No |
| 53321 | TCP | Control channel, JSON-lines | **TLS** |
| 53322 | TCP | Bulk transfer, binary framed | No (token-authed) |
| 53323 | TCP | HTTP media + thumbnails | No (token-authed) |

Multicast group is constrained to `224.0.0.0/24` — some Android devices reject other groups.

---

## 4. Identity and pairing

Each install generates once, on first run:

- A **device ID** — random 128-bit, stable for the install lifetime.
- A **self-signed X.509 certificate** with an Ed25519 or P-256 key.
- A **fingerprint** — SHA-256 of the DER-encoded certificate.

Pairing:

1. Both apps are open; each discovers an *unpaired* peer and offers to pair.
2. The initiator displays a six-digit code derived from the two fingerprints: sort the two
   fingerprints, concatenate, SHA-256, truncate to 6 decimal digits. Sorting makes the
   derivation order-independent, so both devices compute the same code.
3. The user confirms the code matches on the other device.
4. Both persist `{deviceId, fingerprint, displayName}` as the trusted peer.

After pairing, the TLS handshake on 53321 is validated **by fingerprint pin only** — standard
CA validation is meaningless for self-signed local certs and is explicitly disabled. A
certificate that does not match the pinned fingerprint is rejected with no prompt and no
override path.

**Exactly one paired peer at a time.** Re-pairing replaces it. This is a deliberate
simplification of the whole trust model.

Unpaired devices are ignored: they receive no browse response, no file, no metadata. The only
message an unpaired peer can elicit is a pairing offer.

---

## 5. Discovery

Four strategies run **concurrently**. The first to complete a successful TLS handshake and
fingerprint match wins; the others are cancelled.

### S1 — Cached endpoint

Persist a map of network identity to last-known `IP:port`. On start or network change, attempt a
direct connection to the cached address for the current network. Expected to succeed in ~50 ms
and be the common case, since both target networks are stable.

### S2 — Gateway probe

Read the current default gateway and attempt a control-channel handshake against it.

**This is the decisive strategy for the primary scenario.** When the phone is the hotspot, the
phone *is* the PC's default gateway, so the PC resolves the peer deterministically with no
scanning, no multicast, and no dependence on AP behaviour.

### S3 — Multicast

Announce and query on `224.0.0.167:53320`. Payload is a small JSON document carrying device ID,
display name, fingerprint, control port, and protocol version. A device receiving an announce
from its paired peer responds with a **unicast** announce to the sender — the fallback for
networks that deliver multicast in one direction only.

A WiFi multicast lock is acquired on Android for the duration of a discovery burst and released
immediately after. It is never held while idle.

### S4 — Parallel subnet sweep

Enumerate the local /24 and attempt 254 concurrent TCP connections to port 53321 with a short
timeout. Completes in roughly one second. This is the backstop for APs that silently drop
multicast.

Bounded to a /24. Larger subnets fall back to S1–S3 only; sweeping a /16 is not attempted.

### Asymmetry: the phone never scans

Modern Android restricts reading `/proc/net/arp` and the softAP client list. Slipstream needs
neither. In hotspot mode the **PC** locates the phone via S2 and connects inbound; the phone
learns the PC's address from that inbound connection and caches it under S1. The phone only ever
listens and responds.

### Network change handling

Both platforms subscribe to OS connectivity events — `ConnectivityManager.NetworkCallback` on
Android, `NetworkChange.NetworkAddressChanged` on Windows. On any change:

1. Tear down the control channel.
2. Re-run all four discovery strategies against the new network.
3. On reconnect, resume any in-flight transfers from their persisted chunk bitmaps (§7).

A network switch is a normal, expected event — never an error state.

---

## 6. Control channel

A single persistent TLS connection on 53321, framed as **JSON Lines** (one UTF-8 JSON object
per newline-terminated line). Either side may send at any time. Requests carry an `id`;
responses echo it; events carry no `id`.

```
{"id":"7f3a","type":"list","path":"/storage/emulated/0/DCIM","sort":"name"}
{"id":"7f3a","type":"list.ok","entries":[...],"truncated":false}
{"type":"transfer.progress","transferId":"a91c","bytes":48234496,"rate":52428800}
```

### Message families

| Family | Direction | Purpose |
|---|---|---|
| `hello` / `hello.ok` | both | Version negotiation, device info, capability flags |
| `pair.offer` / `pair.confirm` | both | One-time pairing (§4) |
| `list` / `list.ok` | both | Directory listing: name, size, mtime, isDir, mime, thumbnail token |
| `stat` / `stat.ok` | both | Single-entry metadata |
| `pull.request` / `pull.ok` | both | Ask the peer to send a file — returns a bulk token |
| `push.offer` / `push.ok` | both | Offer to send a file — returns a bulk token and target path |
| `transfer.progress` | event | Periodic, throttled to ~4/s |
| `transfer.done` / `transfer.error` | event | Terminal states |
| `play` | both | Instruct the peer to open a stream URL in its default player |
| `clipboard` | both | Text / URL payload, up to 64 KB |
| `ping` / `pong` | both | Liveness, 10 s interval, 30 s dead-peer timeout |

Unknown message types are ignored rather than fatal, so a version skew degrades instead of
breaking the connection.

### Directory listing

Listings are capped at 5000 entries per response with a `truncated` flag. Deeper paging is
deliberately not implemented for v1, because no real directory on either device approaches the
cap and the flag makes the limit honest rather than silent.

---

## 7. Transfer engine

### Why not HTTP

Bulk transfer uses a purpose-built framed protocol on 53322 rather than HTTP on 53323, for three
reasons: parallel range streams need explicit coordination, resume needs a chunk-bitmap exchange
that has no HTTP idiom, and per-chunk integrity needs a trailer HTTP does not provide.

### Wire format

After connecting to 53322, a stream sends a 64-byte header: magic, protocol version, bulk token,
transfer ID, stream index, byte-range start, byte-range length. The peer validates the token
against the one it issued over TLS, then streams raw bytes for that range, followed by a CRC32C
trailer per chunk.

### Parallelism

Default **4 concurrent streams**, user-configurable 1–8 in settings. Each stream is assigned a
disjoint byte range of the same file. This is the single largest throughput win: it defeats
per-connection TCP window limits and hides the latency spikes characteristic of a wireless link.

For a batch of many small files, streams are assigned **whole files** rather than ranges, since
range-splitting a 40 KB file costs more than it saves. Threshold: files below 4 MB are assigned
whole.

### Socket and I/O tuning

- `SO_SNDBUF` / `SO_RCVBUF` = 4 MB
- `TCP_NODELAY` enabled
- Chunk size 1 MB
- Zero-copy: `FileChannel.transferTo` (Android), `TransmitFile` / pooled `RandomAccess` (Windows)
- Destination file preallocated to full size before the first byte
- A single `fsync` at completion, never per chunk

### Security posture of the bulk path

The bulk path is **plaintext**, authenticated by a single-use token issued over the TLS control
channel and scoped to one transfer ID. This is a deliberate trade: encryption would cost a
significant fraction of throughput, and the path is LAN-only, paired-only, and non-routable by
construction (§11). The control channel — which carries pairing, tokens, and all metadata —
remains TLS-protected.

### Resume

For every in-progress transfer the receiver persists, alongside a `.part` file:

- transfer ID, source path, total size, chunk size
- a **sparse chunk bitmap** of completed, CRC-verified chunks

On reconnect after any interruption, the two sides exchange bitmaps and transmit only the gaps.
A `.part` file older than 7 days with no matching peer state is garbage-collected.

### Integrity

CRC32C per chunk — hardware-accelerated on both platforms, effectively free. A chunk failing
verification is re-requested rather than failing the transfer. A whole-file hash is deliberately
not computed: it would require a second full read of the file for no additional guarantee.

### Folder trees

A folder transfer is expanded to a flat file list with relative paths on the sending side, then
enqueued as individual transfers sharing one parent job. Directory structure is recreated on the
receiver from the relative paths. Empty directories are preserved.

---

## 8. Media streaming

The file's **owning** device serves it over HTTP/1.1 on 53323, with full `Range` request support
(`206 Partial Content`, `Accept-Ranges: bytes`). This is what makes seeking instant and keeps the
file off the receiver's disk.

URLs carry a single-use, time-limited token: `http://<peer-ip>:53323/media/<token>`. Tokens
expire 12 hours after issue or on app restart, whichever is first.

### Push-to-play

The flow the user described — pick a file on the phone, the PC starts playing it:

1. Phone requests a stream token for the file over the control channel.
2. Phone sends `{"type":"play","url":"...","title":"...","mime":"video/mp4"}`.
3. PC launches the URL in the system default player.

**Windows implementation note.** Handing a bare `http://` URL to `ShellExecute` opens the
default *browser*, not the default media player — a real and easily-missed failure. Slipstream
instead writes a one-line `.m3u` playlist to temp and launches that, which Windows resolves via
the registered playlist handler (VLC, MPC-HC, PotPlayer, Films & TV). If no playlist handler is
registered, it falls back to a detected known player, and only then to the URL.

**Android implementation note.** The reverse direction uses `Intent.ACTION_VIEW` with the URL and
an explicit MIME type, which resolves correctly to the user's default player or a chooser.

Playback is fire-and-forget. There is no remote control, position sync, or queue — an explicit
scope decision, revisitable later without protocol changes, since `play` is already a one-way
message.

---

## 9. Thumbnails and metadata

Generated on the **owning** device, never by transferring the source file.

- **Android:** `ContentResolver.loadThumbnail` for media-store items; `ThumbnailUtils` for
  arbitrary paths.
- **Windows:** the Shell thumbnail provider (`IShellItemImageFactory`), which yields correct
  thumbnails for essentially every registered file type, including video and documents.

Thumbnails are encoded as JPEG at 256px on the long edge, cached on disk keyed by
`hash(path, mtime, size)`, and served from 53323 under a token. A listing response carries a
thumbnail token per entry rather than inline image data, so listings stay small and thumbnails
load lazily as the user scrolls.

Media metadata — duration, resolution, album art — is extracted alongside and returned in `stat`.

---

## 10. Clipboard and text sharing

A `clipboard` control message carrying UTF-8 text up to 64 KB. The receiver places it on the
system clipboard and shows a notification with a paste affordance. URLs are detected and offered
an open action instead.

Deliberately **not** a continuous clipboard sync — that is a background-battery and
privacy-surface problem for a feature described as explicit sharing. Sending is always an
explicit action.

---

## 11. LAN-only enforcement

A hard architectural guarantee, enforced at four layers:

1. **Bind** — all listening sockets bind to the specific local interface address, never a
   wildcard that could include a cellular interface.
2. **Peer address validation** — any peer address outside RFC1918 (`10/8`, `172.16/12`,
   `192.168/16`) or link-local (`169.254/16`, `fe80::/10`) is refused before the handshake. This
   check runs on both inbound and outbound connections.
3. **Android network binding** — sockets are bound to the specific `Network` object obtained from
   `ConnectivityManager`, so traffic physically cannot route over cellular even if a route exists.
4. **No outbound calls at all** — no telemetry, no analytics, no crash reporting, no update check,
   no font or CDN fetch. Both apps ship with zero network dependencies beyond the peer protocol.

The Android manifest requests `INTERNET` because any socket requires it; layer 2 makes the
guarantee verifiable rather than aspirational, and the test suite asserts it (§17).

---

## 12. UI — Meridian adoption

Slipstream adopts **Meridian**, the design system defined in the VanSale repository at
`VanSalesMain/docs/design-system.md`. That document remains the source of truth for tokens,
principles, and voice; this section records how Slipstream applies them and where it deviates.

### Why Meridian fits

Meridian's thesis is *color is meaning, not decoration*: a quiet cool-gray canvas, white surfaces
bounded by 1px hairlines rather than shadows, exactly one brand blue, and three status colors
reserved for information the reader must act on.

Slipstream's single most important piece of information is connection state, and it maps onto
Meridian's signals with no new tokens invented:

| State | Token | Hex |
|---|---|---|
| Paired and connected | Positive | `#2E9E5B` |
| Discovering / transfer in flight | Info (= Brand) | `#1B62C9` |
| Connected but degraded (2.4 GHz hotspot, slow link) | Warning | `#E08A1E` |
| Peer lost, transfer failed, integrity mismatch | Critical | `#D64545` |

Info deliberately equalling Brand is correct here for the reason Meridian gives: an in-flight
item is not an alarm.

Every status carries a **word and an icon**, never color alone — Meridian's accessibility rule,
and also what makes the state readable at a glance from across a room.

Meridian's **tabular figures** rule is load-bearing rather than cosmetic in this app: rate, ETA,
size, and percent all update several times a second, and proportional figures make the readout
visibly jitter. The **Hero metric** role (40sp, bold, tabular, Brand, one per screen) is the live
transfer rate.

### Deviations from VanSale, and why

| Aspect | VanSale | Slipstream | Reason |
|---|---|---|---|
| Localisation | Bilingual AR/EN, RTL-first | English only, LTR | Personal tool; removes real ongoing work |
| Dark mode | Android yes, web light-only | **Both platforms** | Media app used at night |
| Android toolkit | XML + Material 3 | Compose | Chosen deliberately; requires §13 |

No new color, no second accent, no shadow language. Per Meridian §9, boldness is spent once.

### Android screens

All built from the Meridian §5 kit — no new components invented.

- **Peer / Home** — `Header card` (Brand fill, On-brand title, On-brand-muted subtitle) with
  device name and link state; `Hero metric` showing live rate during a transfer; a row of
  `Icon tile` actions: Send files, Browse PC, Stream to PC, Send clipboard.
- **Browse PC** — the mobile list scaffold verbatim: toolbar → `Search field` → `Filter chip` row
  (All / Video / Audio / Images / Docs) → pull-to-refresh list of `List-row card`s (thumbnail in a
  Tint-filled `sm` tile, Item title, muted meta, right-aligned tabular size) → one state view
  covering the same bounds for loading / empty / error.
- **Transfers** — list-row cards with a bare `LinearProgressIndicator`, a status pill, and tabular
  rate and ETA.
- **History** — the same rows in terminal states, with re-run and reveal actions.
- **Settings** — cards stacked at 20dp gaps in a scroll container, per the detail-screen scaffold.
  Includes the parallel-stream count as a bounded stepper.

### Windows screens

The desktop app is structurally Meridian's *admin web*, not its phone, so it takes the dashboard
half of §5.

- **Sidebar shell** — 260px Surface panel on Canvas: Device, Browse phone, Transfers, History,
  Settings. Active leaf is a **filled Brand pill** with On-brand text; inactive is Ink-muted.
  Per Meridian's explicit rule: no nav search box, no bottom user chip.
- **Top bar** — bold page title and muted subtitle inline-start; connection status pill
  inline-end. No search box.
- **Device** — a 3-up `Stat card` row (link rate, transferred today, peer state): small muted
  label above a large bold tabular number. Below it, the connection panel with discovery detail.
- **Browse phone** — the `Data table`: uppercase-free muted column headers, hairline row
  separators, **no vertical gridlines**, generous row height, right-aligned toolbar
  (Search / Filter / Sort), per-column menu in an `sm`-radius Surface popover, selected row in
  Brand-tint `#EEF0FB` with hover one step lighter. A gallery view toggle for media-heavy folders,
  since thumbnails already exist.
- **Transfers / History** — the same data table with progress and status-badge columns.

---

## 13. `meridian-compose` — porting the design system

Because Slipstream uses Compose, VanSale's `:meridian-ui` Gradle module (XML + Material 3 theme
attributes) cannot be consumed directly. Slipstream builds `:meridian-compose`
(namespace `com.slipstream.meridian`), structured to mirror `:meridian-ui` role-for-role so the
two implementations stay reconcilable.

### Single token source

`MeridianTokens.kt` holds every hex value from `design-system.md` §2 and nothing else. The lint
gate (§17) bans `Color(0x…)` literals anywhere outside this file — the Compose equivalent of the
color-literal check in VanSale's `check-meridian-tokens.sh`.

### Two theming layers, both mandatory

Meridian defines roles Material 3 has no slot for — Canvas, Tint, Ink-muted, Positive, Warning,
Critical. Therefore:

1. **`LocalMeridianColors`** — a CompositionLocal carrying the full Meridian role set, used by
   Slipstream's own components.
2. **A fully-mapped M3 `ColorScheme`** underneath it, so stock Material components are correct
   without per-call-site overrides.

The second layer is not optional. This is VanSale's documented "unmapped role" trap reappearing
in a new toolkit: leave `surfaceVariant` or any tertiary role unmapped and a stock Compose chip
renders Material baseline lavender — no crash, no lint warning, just the wrong color, exactly as
VanSale's filter chip did. **Every role is mapped, in both light and dark, in the same edit.**

### Compose-specific traps, to be encoded in the playbook

1. **`Surface` applies tonal elevation by default,** tinting the surface color. Meridian mandates
   elevation of at most 1dp and "strokes, not shadows", so every card sets `tonalElevation = 0.dp`
   and `shadowElevation = 0.dp`, taking its structure from
   `Modifier.border(1.dp, stroke, shape)`. Missing this drifts every card off-token with nothing
   to catch it.
2. **Tabular figures require `fontFeatureSettings = "tnum"`** on the numeric text styles. There is
   no XML attribute to copy across, and the omission is invisible until a live rate readout starts
   jittering.
3. **The `values-night` trap is replaced, not removed.** Compose has no night-qualified resources,
   so VanSale's single-night-file rule is moot. Its replacement: `isSystemInDarkTheme()` is read
   in **exactly one place**, inside `MeridianTheme`. Read anywhere else and screens disagree about
   which mode they are in.

### Components to port

`MeridianCard`, `MeridianListRow`, `MeridianIconTile`, `MeridianStatusPill`, `MeridianStat`,
`MeridianStateView`, `MeridianSectionHeader`, `MeridianSearchField`, `MeridianHeaderCard`,
`MeridianHeroMetric`, `MeridianStepper`, plus themed button, chip, and badge wrappers.

Shapes: `sm` 12dp, `md` 14dp, `lg` 16dp, `pill` = `RoundedCornerShape(50)`.
Spacing: the 4pt grid — 4, 8, 12, 16, 20, 24.

### Windows equivalent

A WinUI 3 `ResourceDictionary` pair (Light / Dark) defining the same token names as brushes, plus
custom controls for the §12 desktop kit. The same trap applies: an unmapped `ThemeResource`
silently resolves to the system Fluent accent, so the full brush set is defined in both
dictionaries together.

---

## 14. Background operation and autostart

**Android** — a foreground service with a low-priority persistent notification, unavoidable
post-Oreo. Started on `BOOT_COMPLETED` and on app launch. Idle cost is negligible: one bound UDP
socket and one accepting TCP socket. The WiFi multicast lock is acquired only during discovery
bursts and released immediately, never held while idle. A battery-optimisation exemption is
requested once, with an explanation, and the app functions — with slower reconnection — if denied.

**Windows** — a Task Scheduler entry at logon, running to the system tray. Closing the main window
hides to tray rather than exiting. The tray menu offers Show, Pause discovery, and Quit.

---

## 15. Errors and voice

Following Meridian §7 — direct, no apology, name the next step:

| Condition | Message |
|---|---|
| Peer not found | *"Phone not on this network. Searching…"* — with per-strategy progress visible, not an opaque spinner |
| Transfer interrupted | *"Connection lost — resuming from 4.2 GB."* The resume **is** the message |
| Integrity failure | *"Chunk verification failed — retrying that block."* |
| Degraded link | A Warning-colored chip: *"2.4 GHz — slower link"*, so observed speed is explained rather than mysterious |
| Unpaired peer seen | *"Slipstream found a device. Pair it?"* |
| Storage permission denied | *"Slipstream needs file access to browse this device."* with the action attached |

Empty states invite action per Meridian §7: an empty transfer list says what to do, never
"No data."

---

## 16. Throughput expectations

Recorded here so implementation is measured against reality rather than hope.

| Link | Realistic range |
|---|---|
| WiFi 5 / 6 through a router, both devices connected | 40–100 MB/s |
| Android softAP hotspot, PC as client | Substantially lower — commonly 3–5 MB/s |
| PC-hosted hotspot, phone as client | Typically better than the above |
| 5 GHz softAP vs 2.4 GHz | Roughly 5× |

The Android hotspot case is limited by the phone running access-point and client duty on one
radio, usually falling back to 2.4 GHz. **No software change recovers this.** The design's
responses are: (a) surface the active band and rate so the user understands what they are seeing,
(b) offer a "PC hosts the hotspot" mode in settings, and (c) ensure the parallel-stream design
extracts whatever the radio does provide.

---

## 17. Testing

| Suite | Covers |
|---|---|
| **Protocol conformance vectors** | A shared fixture set both implementations run against: bulk framing, resume bitmap exchange, `Range` responses, pairing-code derivation, token validation |
| **Discovery matrix** | All four strategies × phone-hotspot / external-WiFi / PC-hotspot, plus mid-transfer network switch. The highest-risk area |
| **Throughput regression** | A benchmark failing the build if throughput drops below a floor, so an accidental buffer copy cannot silently halve performance |
| **LAN-only assertion** | A test asserting a non-RFC1918 peer address is refused at every entry point |
| **Resume correctness** | Kill the connection at randomised offsets; assert byte-identical output |
| **Token lint** | Bans `Color(0x…)` outside `MeridianTokens.kt` (Android) and hardcoded brushes outside the dictionaries (Windows) |
| **Theme composition** | Every screen composes in forced light and forced dark without throwing — the Compose successor to VanSale's instrumented theme-inflation test |
| **Component gallery** | A debug-only screen rendering every token and component against the live theme, plus a `@Preview` per component in both modes |

---

## 18. Repository structure

```
Slipstream/
├── README.md
├── docs/
│   ├── design-guide.md          # Meridian as applied here (§12) — written during implementation
│   ├── design-playbook.md       # Procedure + Compose traps (§13)
│   ├── protocol.md              # The wire spec, source of truth for both apps
│   └── superpowers/specs/
├── android/
│   ├── app/
│   └── meridian-compose/        # §13
├── windows/
│   ├── Slipstream.App/          # WinUI 3
│   ├── Slipstream.Core/         # discovery, transfer, media server
│   └── Slipstream.Meridian/     # resource dictionaries + controls
└── protocol/
    └── vectors/                 # shared conformance fixtures
```

`docs/protocol.md` is extracted from §4–§10 during implementation and becomes the authority both
apps are tested against.

---

## 19. Deferred

Recorded so they are visible decisions rather than oversights:

- Embedded player with remote control, position sync, and a queue. The `play` message is already
  one-way, so this needs no protocol change to add later.
- Continuous clipboard sync.
- More than two paired devices.
- Continuous folder sync.
- Directory listing paging beyond the 5000-entry cap.
- Arabic / RTL support.
