<div align="center">

# Slipstream

**Your phone and your PC, one filesystem, at wire speed.**

*Zero-config peer-to-peer file transfer and media streaming between Android and Windows.
Local network only. No cloud, no account, no internet — ever.*

[![Status](https://img.shields.io/badge/status-design-1B62C9?style=flat-square)](docs/superpowers/specs)
[![Android](https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-2E9E5B?style=flat-square)](#)
[![Windows](https://img.shields.io/badge/Windows-.NET%209%20%2B%20WinUI%203-2E9E5B?style=flat-square)](#)
[![Network](https://img.shields.io/badge/network-LAN%20only-D64545?style=flat-square)](#never-the-internet)
[![License](https://img.shields.io/badge/license-MIT-8A8D9B?style=flat-square)](LICENSE)

</div>

---

## The idea

You already have a fast local network. When your laptop is on your phone's hotspot, the two
devices are *one hop apart* — closer than any cloud service will ever be. Slipstream is built
on the observation that this link is almost always sitting there unused, while your files take
a round trip through a datacenter on another continent to travel three feet.

So: **no upload, no sync, no account.** Two peers find each other in about a second, on any
network, and move bytes as fast as the radio physically allows.

## What it does

<table>
<tr><td width="33%" valign="top">

### Transfer
Push or pull, from either device. Whole folder trees with structure preserved. Resumable —
walk out of WiFi range mid-transfer and it picks up from the exact byte when you're back.

</td><td width="33%" valign="top">

### Browse
Your phone's storage on your PC, your PC's drives on your phone. Real thumbnails, durations,
album art. It reads like a gallery, not a directory listing.

</td><td width="33%" valign="top">

### Stream
Actual streaming, not download-then-play. Range-request HTTP, so seeking is instant and
nothing ever lands on disk.

</td></tr>
</table>

**Either end can start anything.** Pick a movie on your phone, and your PC starts playing it —
you never touch the PC. Send a link, a snippet, or the clipboard the same way.

## Zero configuration, genuinely

Pair once with a six-digit code. After that the two devices find each other silently on any
network you put them on, forever.

Discovery runs **four strategies in parallel** and takes whichever answers first:

| | Strategy | Wins when |
|---|---|---|
| **1** | Cached endpoint | The usual case — last known address, ~50 ms |
| **2** | Gateway probe | **Phone hotspot.** The phone *is* the gateway, so this is instant and cannot fail |
| **3** | UDP multicast | External WiFi, well-behaved AP |
| **4** | Parallel subnet sweep | The AP eats multicast — 254 concurrent probes, ~1 s |

Switch from hotspot to café WiFi mid-transfer and it re-discovers, reconnects, and resumes.

## Built for speed, honestly

The bulk data path deliberately isn't HTTP and isn't encrypted:

- **Parallel TCP streams**, each pulling a different byte range of the same file — defeats
  per-connection window limits and hides wireless latency spikes
- **Zero-copy** on both ends — `FileChannel.transferTo` / `TransmitFile`. Disk to NIC, no
  userspace round trip
- **Plaintext bulk, token-authenticated** over a TLS control channel. Paired-only, LAN-only,
  never routable — so you don't pay an encryption tax on every byte
- 4 MB socket buffers, `TCP_NODELAY`, 1 MB chunks, preallocated targets, hardware CRC32C

**The honest part:** the protocol won't be your bottleneck — the radio will. On WiFi 5/6 through
a router, expect 40–100 MB/s. Through an Android hotspot, expect *considerably* less, because
the phone is running access-point and client duty on one radio and usually drops to 2.4 GHz.
No software fixes that. Slipstream shows you which link you're on and why it's slow, instead of
letting you wonder.

## Never the internet

This is a hard architectural guarantee, not a preference:

- Sockets bind to the local interface only
- Any peer outside RFC1918 / link-local is refused outright
- On Android, sockets bind to the specific `Network` object — traffic **cannot** route over cellular
- Zero outbound calls. No telemetry, no analytics, no update check, no crash reporting

## Design

Slipstream wears **Meridian** — a calm instrument-panel design language built on one thesis:
*color is meaning, not decoration.* A quiet cool-gray canvas, white cards separated by 1px
hairlines rather than shadows, exactly one brand blue, and three status colors that only ever
appear when they carry real information.

It fits this app almost too well. The thing you most need to know is connection state, and it
maps one-to-one onto the signals — connected, in-flight, degraded, lost — each with a word and
an icon, never color alone. And since the app is made of numbers (MB/s, ETA, size, percent),
Meridian's tabular-figures rule keeps the speed readout from jittering as it updates.

## Architecture

Both apps are the same six modules, mirrored. There is no client and no server — each device
runs a full peer, which is why "either end can initiate" falls out for free.

```
┌─────────────────────┐                          ┌─────────────────────┐
│   Android (Kotlin)  │                          │  Windows (.NET 9)   │
│   Compose + Media3  │                          │      WinUI 3        │
├─────────────────────┤                          ├─────────────────────┤
│ Discovery           │ ◀── 53320/udp  ────────▶ │ Discovery           │
│ Identity / Pairing  │                          │ Identity / Pairing  │
│ Control channel     │ ◀── 53321/tcp  TLS ────▶ │ Control channel     │
│ Transfer engine     │ ◀── 53322/tcp  bulk ───▶ │ Transfer engine     │
│ Media server        │ ◀── 53323/tcp  HTTP ───▶ │ Media server        │
│ UI  (Meridian)      │                          │ UI  (Meridian)      │
└─────────────────────┘                          └─────────────────────┘
```

Control is small, encrypted, always connected — browse requests, play commands, clipboard,
progress events. Bulk is a separate socket pool that does nothing but move bytes.

## Status

**Design complete, implementation not started.**
The full specification lives in [`docs/superpowers/specs/`](docs/superpowers/specs).

## License

MIT
