# Plan 3 — Android Core

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kotlin implementation of Slipstream's six modules — identity, discovery, control, pairing, transfer, media — wire-compatible with the shipped Windows core, proven against the shared conformance vectors and against a live Windows peer.

**Architecture:** A `:core` Gradle library with no Android UI dependency, structured module-for-module against `Slipstream.Core` so the two stay reconcilable. Coroutines and `Dispatchers.IO` replace `async`/`await`; `SelectorProvider` sockets replace `TcpClient`. A foreground service hosts it.

**Tech Stack:** Kotlin 2.0, coroutines, `kotlinx.serialization`, Conscrypt-free platform TLS, JUnit4 + Robolectric, `minSdk 26`.

**Spec:** [`2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §3–§11, §14.

**Normative wire specs (already merged, do not re-derive):**
`protocol/protocol.md`, `protocol/bulk-format.md`, `protocol/pairing.md`, and the vectors in `protocol/vectors/`.

---

## Preconditions

Plans 1, 1b, 2, and 4a are merged on `main`. This plan depends on the **documents and vectors**, not on any C# type:

```
protocol/bulk-format.md            # 64-byte header, chunk framing, bitmap encoding
protocol/pairing.md                # pair.* flow, code derivation, threat model
protocol/protocol.md               # ports, announcements, JSON-lines framing, hello/ping
protocol/vectors/pairing-codes.json
protocol/vectors/announcements.json
protocol/vectors/bulk-headers.json
protocol/vectors/chunk-bitmaps.json
protocol/vectors/crc32c.json
```

`android/` already exists with `settings.gradle.kts`, the version catalogue, and `:meridian-compose`. This plan adds `:core` alongside it.

**Read `protocol/bulk-format.md` before Task 8.** It is normative and the C# side already conforms to it. Where this plan and that document disagree, the document wins — say so and record it.

## Global Constraints

- **Module:** `:core`, namespace `com.slipstream.core`, `minSdk 26`, no dependency on `:app` or `:meridian-compose`.
- **Every conformance vector must pass.** They are the only thing keeping two independent implementations on one protocol.
- **Wire format is big-endian.** Kotlin's `ByteBuffer` defaults to big-endian; do not "helpfully" set `LITTLE_ENDIAN`. The chunk **bitmap** is the one little-endian-bit-order structure — bit *i* of byte *n* is chunk `n*8 + i`.
- **Ports:** 53320/UDP, 53321/TCP TLS, 53322/TCP bulk, 53323/TCP HTTP. Multicast `224.0.0.167`, inside `224.0.0.0/24`.
- **Protocol version 1.** Unknown control message types are ignored, never fatal.
- **TLS is fingerprint-pin only**; platform CA validation explicitly disabled. Non-matching certificate rejected with no prompt and no override.
- **Exactly one paired peer.** Re-pairing replaces it.
- **Pairing requires an open 120-second window**; outside it, unpaired connections are dropped before a message is read.
- **LAN-only, four layers:** bind to the interface; refuse any peer outside RFC1918/link-local/ULA; **bind every socket to the `Network` object** from `ConnectivityManager` so traffic cannot route over cellular; zero outbound calls of any kind.
- **Transfer:** 1 MB chunks, 4 streams default (1–8), files < 4 MB assigned whole, 4 MB socket buffers, `TCP_NODELAY`, preallocated destination, one fsync at completion, CRC32C per chunk, chunk-bitmap resume.
- **Storage:** `MANAGE_EXTERNAL_STORAGE` (sideload-only, per §2). Use `java.io.File` paths, not SAF.
- **The phone never scans.** No `/proc/net/arp`, no softAP client list. It listens and responds; the PC finds it.
- **No third-party networking or crypto dependencies.** Platform APIs only.
- **User-facing strings:** English, sentence case, direct, no apology.

---

## File Structure

```
android/core/
  build.gradle.kts
  src/main/kotlin/com/slipstream/core/
    SlipstreamPorts.kt
    net/LanGuard.kt              net/NetworkInfo.kt
    identity/DeviceIdentity.kt   identity/Fingerprint.kt
    identity/PairingCode.kt      identity/PairedPeerStore.kt
    discovery/PeerAnnouncement.kt  discovery/EndpointCache.kt
    discovery/DiscoveryStrategy.kt discovery/CachedEndpointStrategy.kt
    discovery/GatewayProbeStrategy.kt discovery/MulticastStrategy.kt
    discovery/SubnetSweepStrategy.kt  discovery/DiscoveryCoordinator.kt
    control/ControlMessage.kt    control/JsonLineCodec.kt
    control/PinnedTls.kt         control/ControlConnection.kt
    control/ControlServer.kt     control/ControlClient.kt
    control/SlipstreamSession.kt
    pairing/PairingWindow.kt     pairing/PairingSession.kt
    pairing/PairingDiscovery.kt  pairing/PairingCoordinator.kt
    transfer/BulkFrameHeader.kt  transfer/Crc32C.kt
    transfer/ChunkBitmap.kt      transfer/TransferPlan.kt
    transfer/PartFile.kt         transfer/TokenVault.kt
    transfer/BulkServer.kt       transfer/BulkClient.kt
    transfer/TransferEngine.kt
    files/FileBrowser.kt         media/MediaServer.kt
    media/ThumbnailProvider.kt
    SlipstreamPeer.kt
  src/main/AndroidManifest.xml   # permissions + foreground service
  src/test/kotlin/…              # JVM + Robolectric
android/app/                     # thin host: foreground service + boot receiver
```

---

## Task 1: `:core` module scaffold and the vector harness

**Files:** `android/core/build.gradle.kts`, `settings.gradle.kts` update, `src/test/kotlin/com/slipstream/core/Vectors.kt`

- [ ] **Step 1: Add the module**

Add `include(":core")` to `android/settings.gradle.kts`. Create `android/core/build.gradle.kts` as an `com.android.library` with `namespace = "com.slipstream.core"`, `minSdk 26`, `jvmTarget 17`, `-Werror`, and dependencies on `kotlinx-coroutines-android`, `kotlinx-serialization-json`, plus test deps `junit`, `robolectric`, `kotlinx-coroutines-test`.

- [ ] **Step 2: Write the vector locator**

```kotlin
package com.slipstream.core

import java.io.File

/**
 * Resolves protocol/vectors/ from the test working directory. These fixtures are the
 * only thing keeping this implementation and the C# one on the same protocol, so
 * failing to find them must be loud, not a silently skipped test.
 */
object Vectors {
    val root: File by lazy {
        generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .firstOrNull { File(it, "protocol/vectors").isDirectory }
            ?.let { File(it, "protocol/vectors") }
            ?: error("Could not locate protocol/vectors from ${System.getProperty("user.dir")}")
    }

    fun read(name: String): String = File(root, name).readText()
}
```

- [ ] **Step 3: Prove the locator works**

```kotlin
@Test fun `finds the shared conformance vectors`() {
    assertTrue(Vectors.read("crc32c.json").contains("Castagnoli"))
}
```

Run `cd android && ./gradlew :core:testDebugUnitTest --tests '*Vectors*'` → PASS.

- [ ] **Step 4: Commit**

```bash
git add android && git commit -m "chore: scaffold the Android :core module and vector harness"
```

---

## Task 2: `LanGuard`, ports, `NetworkInfo`

**Files:** `SlipstreamPorts.kt`, `net/LanGuard.kt`, `net/NetworkInfo.kt`, tests

**Produces:** `object SlipstreamPorts { const val DISCOVERY = 53320; CONTROL = 53321; BULK = 53322; MEDIA = 53323; PROTOCOL_VERSION = 1; val MULTICAST_GROUP: InetAddress }`; `object LanGuard { fun isLocal(a: InetAddress): Boolean; fun ensureLocal(a: InetAddress) }` throwing `NonLocalAddressException`; `data class LocalNetwork(val localAddress: InetAddress, val gateway: InetAddress?, val prefixLength: Int, val key: String)`; `interface NetworkInfo { fun current(): LocalNetwork? }`; `object SubnetMath { fun enumerateHosts(a: InetAddress, prefix: Int): Sequence<InetAddress> }`.

- [ ] **Step 1: Write the failing test**

Mirror the C# `LanGuardTests` exactly — the same accept list (10/8, 172.16/12, 192.168/16, 169.254/16, 127/8, ::1, fe80::/10, fc00::/7) and the same near-miss rejects (`172.15.255.255`, `172.32.0.1`, `192.167.1.1`, `11.0.0.1`, `8.8.8.8`, `2001:4860:4860::8888`). Both implementations must agree on what "local" means, or the two devices will disagree about whether they may talk.

Also mirror `SubnetMathTests`: 254 hosts for a /24, network and broadcast excluded, **empty for anything wider than /24**, 126 hosts for a /25.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Implement**

```kotlin
package com.slipstream.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class NonLocalAddressException(val address: InetAddress) :
    Exception("Refused non-local address $address. Slipstream never leaves the local network.")

/**
 * Spec §11 layer 2: the only addresses Slipstream will connect to or accept from.
 * Applied to inbound and outbound connections alike. Must agree byte-for-byte with
 * the C# LanGuard, or the two ends disagree about whether they are allowed to talk.
 */
object LanGuard {
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isLocalV4(address.address)
        is Inet6Address -> isLocalV6(address)
        else -> false
    }

    private fun isLocalV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        return when {
            o0 == 10 -> true                       // 10.0.0.0/8
            o0 == 172 && o1 in 16..31 -> true      // 172.16.0.0/12
            o0 == 192 && o1 == 168 -> true         // 192.168.0.0/16
            o0 == 169 && o1 == 254 -> true         // link-local
            o0 == 127 -> true                      // loopback, for tests
            else -> false
        }
    }

    private fun isLocalV6(address: Inet6Address): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        return (address.address[0].toInt() and 0xFE) == 0xFC   // fc00::/7 ULA
    }

    fun ensureLocal(address: InetAddress) {
        if (!isLocal(address)) throw NonLocalAddressException(address)
    }
}
```

`NetworkInfo` reads the active `LinkProperties` from `ConnectivityManager`: first IPv4 `LinkAddress` that is local, the default route's gateway, the prefix length, and a key of `"${network.networkHandle}|$networkAddress/$prefix"`.

- [ ] **Step 4: Run, commit**

---

## Task 3: Identity, fingerprint, pairing code

**Files:** `identity/Fingerprint.kt`, `identity/DeviceIdentity.kt`, `identity/PairingCode.kt`, `identity/PairedPeerStore.kt`, tests

**Produces:** `Fingerprint.of(cert)` → lowercase hex SHA-256 of DER; `DeviceIdentity` with `deviceId` (32 hex chars), `displayName`, `certificate`, `privateKey`, `fingerprint`, plus `loadOrCreate(dir, name)`; `PairingCode.derive(a, b)`; `PairedPeer` + `PairedPeerStore`.

- [ ] **Step 1: Write the failing test — vectors first**

```kotlin
@Test fun `pairing codes match the shared vectors`() {
    val cases = Json.parseToJsonElement(Vectors.read("pairing-codes.json"))
        .jsonObject["cases"]!!.jsonArray

    for (case in cases) {
        val o = case.jsonObject
        assertEquals(
            o["code"]!!.jsonPrimitive.content,
            PairingCode.derive(o["a"]!!.jsonPrimitive.content, o["b"]!!.jsonPrimitive.content),
        )
    }
}

@Test fun `derivation is order independent`() {
    val a = "0".repeat(64); val b = "f".repeat(64)
    assertEquals(PairingCode.derive(a, b), PairingCode.derive(b, a))
}

@Test fun `certificate is self-signed with a usable private key`() {
    val identity = DeviceIdentity.createNew("Test Phone")
    assertEquals(64, identity.fingerprint.length)
    assertTrue(identity.fingerprint.matches(Regex("^[0-9a-f]{64}$")))
    assertEquals(identity.certificate.subjectX500Principal, identity.certificate.issuerX500Principal)
}

@Test fun `loadOrCreate is stable across instances`() {
    val dir = createTempDirectory().toFile()
    assertEquals(
        DeviceIdentity.loadOrCreate(dir, "Test").fingerprint,
        DeviceIdentity.loadOrCreate(dir, "Test").fingerprint,
    )
}
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Implement**

```kotlin
/**
 * Spec §4. Sorting the two fingerprints before hashing makes the derivation
 * order-independent, so both devices compute the same code without negotiating who
 * is "first". Must match protocol/vectors/pairing-codes.json exactly — the C# side
 * already does.
 */
object PairingCode {
    fun derive(fingerprintA: String, fingerprintB: String): String {
        val a = fingerprintA.trim().lowercase()
        val b = fingerprintB.trim().lowercase()
        val (first, second) = if (a <= b) a to b else b to a

        val digest = MessageDigest.getInstance("SHA-256")
            .digest((first + second).toByteArray(Charsets.US_ASCII))

        // First four bytes, big-endian, as an unsigned 32-bit value.
        val value = ByteBuffer.wrap(digest, 0, 4).int.toUInt()

        return (value % 1_000_000u).toString().padStart(6, '0')
    }
}
```

Certificate generation uses `KeyPairGenerator.getInstance("EC")` with P-256 and a self-signed X.509 built via `android.security.keystore` or a hand-rolled DER writer; persist as PKCS#12 alongside the device id. `PairedPeerStore` is a JSON file holding at most one peer, degrading to unpaired on parse failure only (matching the C# ruling).

- [ ] **Step 4: Run, commit**

---

## Task 4: Announcements and the endpoint cache

**Files:** `discovery/PeerAnnouncement.kt`, `discovery/EndpointCache.kt`, tests

- [ ] **Step 1: Write the failing test** — round-trip; **the announcements vector file must match byte-for-byte**, including field order (`v`, `deviceId`, `name`, `fingerprint`, `control`, `kind`); `tryParse` returns null for empty, non-JSON, `{}`, missing fields, and a future version; payload stays under 1024 bytes (an oversized announcement fragments and some APs drop it).

- [ ] **Step 2–4: Implement, test, commit**

`@Serializable` data class with `@SerialName` matching the vectors. `tryParse` catches `SerializationException` and returns null — this parses untrusted network data.

---

## Task 5: Discovery strategies and the coordinator

**Files:** the five discovery files, tests

**Produces:** `fun interface PeerProbe { suspend fun probe(endpoint: InetSocketAddress): DiscoveredPeer? }`; `interface DiscoveryStrategy { val name: String; suspend fun find(network: LocalNetwork): DiscoveredPeer? }`; the four strategies; `DiscoveryCoordinator.discover(timeout): DiscoveryResult?`.

- [ ] **Step 1: Write the failing test**

Mirror the C# suites, plus the coordinator behaviours that matter:

```kotlin
@Test fun `returns the fastest strategy's result and cancels the losers`() = runTest {
    val slow = StubStrategy("slow", delayMs = 3000, result = peer("192.168.1.10"))
    val fast = StubStrategy("fast", delayMs = 50, result = peer("192.168.1.9"))

    val result = DiscoveryCoordinator(StubNetworkInfo(network()), cache, listOf(slow, fast))
        .discover(10.seconds)

    assertEquals("fast", result!!.strategyName)
    assertTrue(slow.wasCancelled)
}

@Test fun `a throwing strategy does not prevent another from winning`() = runTest {
    // On a device where multicast is blocked the socket call raises; that must not
    // take discovery down with it.
    val result = DiscoveryCoordinator(
        StubNetworkInfo(network()), cache,
        listOf(ThrowingStrategy(), StubStrategy("good", 50, peer("192.168.1.9"))),
    ).discover(5.seconds)

    assertEquals("good", result!!.strategyName)
}
```

- [ ] **Step 2–4: Implement, test, commit**

The coordinator races with `select` over `async` deferreds inside a `coroutineScope`, cancelling siblings on the first non-null. A strategy that throws is caught and treated as "found nothing".

`MulticastStrategy` uses **one** receive loop fanning datagrams out to the responder and to per-call subscribers via `Channel`. This is not a style preference: the C# implementation shipped two concurrent receive loops on one socket, they stole each other's datagrams, and it was the one Critical bug that branch produced. Acquire a `WifiManager.MulticastLock` for the burst and release it immediately — never hold it idle.

`SubnetSweepStrategy` launches up to 254 probes with a `Semaphore`, bounded to a /24 by `SubnetMath`.

---

## Task 6: Control channel — JSON lines and pinned TLS

**Files:** `control/ControlMessage.kt`, `JsonLineCodec.kt`, `PinnedTls.kt`, `ControlConnection.kt`, `ControlClient.kt`, `ControlServer.kt`, tests

- [ ] **Step 1: Write the failing test**

Round-trip a request with a payload; one message per line; events carry no `id`; **a malformed line is skipped, not fatal**; a line over 1 MB throws; and the TLS pin:

```kotlin
@Test fun `client refuses a server whose fingerprint is not pinned`() = runTest {
    val server = startTlsServer(DeviceIdentity.createNew("Server"))
    assertFailsWith<Exception> {
        PinnedTls.connect(server.endpoint, DeviceIdentity.createNew("Client")) { false }
    }
}

@Test fun `server drops a connection from an untrusted fingerprint`() = runTest {
    // Matches the C# guarantee exactly: unpaired devices get nothing, and the
    // connection is dropped before a single message is read.
    val handled = AtomicBoolean(false)
    val server = ControlServer(identity, emptyPeerStore, loopback, port = 0)
    server.onPeerConnected = { _ -> handled.set(true) }

    connectAsStranger(server.listenEndpoint)

    assertFalse(handled.get())
}
```

- [ ] **Step 2–4: Implement, test, commit**

`PinnedTls` builds an `SSLContext` with a `TrustManager` that ignores the CA chain entirely and compares `SHA-256(cert.encoded)` against the pin. Read a byte at a time in the codec — messages are small and infrequent, and a buffered reader would have to hand back over-read bytes if the socket is ever repurposed.

---

## Task 7: Pairing

**Files:** the four `pairing/` files, tests

Implements `protocol/pairing.md` — read it first; it is normative and the Windows side already conforms.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `an offer whose claimed fingerprint differs from the certificate is rejected`() {
    // The payload is peer-supplied text; the certificate is the only thing the
    // handshake proves. Only the certificate may drive the code.
    val session = PairingSession(local)
    session.receiveOffer(offer.copy(fingerprint = "not-what-it-holds"), verifiedFingerprint = remote.fingerprint)

    assertEquals(PairingState.Cancelled, session.state)
    assertNull(session.code)
}

@Test fun `a single-sided confirmation never pairs`() {
    val session = started()
    session.confirmLocally()
    assertEquals(PairingState.AwaitingConfirmation, session.state)
    assertNull(session.result)
}

@Test fun `outside an open window an unpaired connection is still dropped`() = runTest {
    val server = ControlServer(identity, emptyPeerStore, loopback, 0, PairingWindow()) // never opened
    var pairingReached = false
    server.onPairingConnected = { pairingReached = true }

    connectAsStranger(server.listenEndpoint)

    assertFalse(pairingReached)
}
```

- [ ] **Step 2–4: Implement, test, commit**

Same shape as the C# side: a 120-second window, a restricted handler speaking only `pair.*`, code derived from both certificate fingerprints, and mutual confirmation before either device persists.

---

## Task 8: Bulk wire format — header, CRC32C, bitmap, plan

**Files:** `transfer/BulkFrameHeader.kt`, `Crc32C.kt`, `ChunkBitmap.kt`, `TransferPlan.kt`, tests

**Read `protocol/bulk-format.md` first.**

- [ ] **Step 1: Write the failing test — vectors are the acceptance criterion**

```kotlin
@Test fun `headers match the shared vectors byte for byte`() {
    for (case in vectorCases("bulk-headers.json")) {
        val f = case["fields"]!!.jsonObject
        val header = BulkFrameHeader(
            version = f["version"]!!.jsonPrimitive.int.toUShort(),
            streamIndex = f["streamIndex"]!!.jsonPrimitive.int.toUShort(),
            token = uuidFromHex(f["token"]!!.jsonPrimitive.content),
            transferId = uuidFromHex(f["transferId"]!!.jsonPrimitive.content),
            rangeStart = f["rangeStart"]!!.jsonPrimitive.long,
            rangeLength = f["rangeLength"]!!.jsonPrimitive.long,
            chunkSize = f["chunkSize"]!!.jsonPrimitive.int,
        )
        assertEquals(
            case["bytes"]!!.jsonPrimitive.content.replace("_", ""),
            header.toBytes().toHex(),
        )
    }
}

@Test fun `crc32c matches the shared vectors`() {
    for (case in vectorCases("crc32c.json")) {
        assertEquals(
            case["crc_hex"]!!.jsonPrimitive.content,
            "%08x".format(Crc32C.compute(case["input_utf8"]!!.jsonPrimitive.content.toByteArray())),
        )
    }
}

@Test fun `chunk bitmaps match the shared vectors`() {
    for (case in vectorCases("chunk-bitmaps.json")) {
        val bitmap = ChunkBitmap(case["chunkCount"]!!.jsonPrimitive.int)
        case["complete"]!!.jsonArray.forEach { bitmap[it.jsonPrimitive.int] = true }
        assertEquals(case["base64"]!!.jsonPrimitive.content, bitmap.toBase64())
    }
}

@Test fun `splitMissing assigns a small file whole`() {
    // Spec §7: below 4 MB, never range-split. The C# side learned this the hard way
    // — its threshold check lived in a method the download path never called.
    val bitmap = ChunkBitmap(ChunkBitmap.chunkCountFor(3L * CHUNK, CHUNK))
    assertEquals(1, TransferPlan.splitMissing(bitmap, 3L * CHUNK, streamCount = 4, CHUNK).size)
}
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Implement**

`Crc32C` uses `java.util.zip.CRC32C` (API 26+) — hardware-accelerated on ARM64 via the platform, no third-party dependency. Verify its check value is `0xE3069283` for `"123456789"`; if the platform disagrees, fall back to a table implementation with the reflected polynomial `0x82F63B78` and record it.

`ChunkBitmap` stores a `ByteArray`; bit *i* of byte *n* is chunk `n*8 + i` — little-endian bit order, matching the vectors, **regardless** of the big-endian wire integers. Getting these two backwards is the likeliest failure here.

- [ ] **Step 4: Run, commit**

---

## Task 9: Part file, token vault, bulk server and client

**Files:** `transfer/PartFile.kt`, `TokenVault.kt`, `BulkServer.kt`, `BulkClient.kt`, tests

- [ ] **Step 1: Write the failing test**

Preallocation; positioned writes at arbitrary offsets; CRC mismatch throws and leaves the bit clear; reopening restores the bitmap; short final chunk; parallel-stream reassembly is byte-identical; and the two lessons from the Windows review:

```kotlin
@Test fun `resumes a bitmap with more gaps than streams`() = runTest {
    // The shape a dropped 4-stream transfer actually leaves behind. The C# side
    // failed here: its token allowed only `streams` uses.
    seedFragmented(transferId, completed = intArrayOf(0, 3, 6, 9, 12, 15))
    val token = vault.issueBulk(transferId, sourcePath, size, expectedStreams = 4)

    PartFile.openOrCreate(destination, transferId, size, CHUNK).use { part ->
        assertTrue(part.bitmap.missingRanges().count() > 4)
        BulkClient().download(serverEndpoint, transferId, token.value, part, streams = 4, null)
        assertTrue(part.complete())
    }
    assertContentEquals(sourceData, destination.readBytes())
}

@Test fun `does not rewrite the sidecar on every chunk`() = runTest {
    // Per-chunk persistence under a global lock serialises every parallel stream.
    val writes = AtomicInteger()
    PartFile.openOrCreate(destination, transferId, 50L * CHUNK, CHUNK, onPersist = { writes.incrementAndGet() })
        .use { part -> repeat(50) { part.writeChunk(it, chunk, crc) } }

    assertTrue(writes.get() < 20, "sidecar rewritten ${writes.get()} times for 50 chunks")
}
```

- [ ] **Step 2–4: Implement, test, commit**

Bulk tokens are **use-unlimited within a 5-minute expiry**, scoped to one transfer id and one path — the Windows side reached the same conclusion in Plan 2b, and the two must agree. Sidecar persistence is debounced to at most every 500 ms plus a flush on close. The bitmap lock covers the bit flip only; file I/O happens outside it.

---

## Task 10: Transfer engine, folder expansion, file browser

**Files:** `transfer/TransferEngine.kt`, `FolderExpander.kt`, `files/FileBrowser.kt`, tests

- [ ] **Step 1: Write the failing test** — pull is byte-identical end to end; progress reaches the total; folder trees flatten to `/`-separated relative paths with empty directories preserved; listings cap at 5000 with a `truncated` flag; directories sort before files; MIME inferred from extension.

- [ ] **Step 2–4: Implement, test, commit**

The retry path reconnects through `ControlClient` — reusing the connection that just died is what made the C# retry useless for its primary case.

---

## Task 11: Media server and thumbnails

**Files:** `media/MediaServer.kt`, `media/RangeHeader.kt`, `media/ThumbnailProvider.kt`, tests

- [ ] **Step 1: Write the failing test** — whole file is `200` with `Accept-Ranges: bytes`; a range is `206` with correct bytes and `Content-Range`; open-ended and suffix ranges; unsatisfiable is `416`; unknown token is `404`; and thumbnails:

```kotlin
@Test fun `generates a thumbnail for an image`() {
    val thumbnail = provider.generate(makeJpeg())
    assertNotNull(thumbnail)
    assertTrue(thumbnail!!.length() > 0)
}
```

**Do not stub this and comment the tests out.** That is exactly what happened on the Windows side and it survived to code review with a green suite. If `ThumbnailUtils` cannot produce one, `generate` returns null for *that file* — the method still works.

- [ ] **Step 2–4: Implement, test, commit**

`ContentResolver.loadThumbnail` for media-store items, `ThumbnailUtils.createImageThumbnail` / `createVideoThumbnail` for arbitrary paths. JPEG at 256px on the long edge, cached by `hash(path, mtime, size)`, served under a token. Listings carry a token, never inline image data.

---

## Task 12: Session, peer facade, foreground service

**Files:** `control/SlipstreamSession.kt`, `SlipstreamPeer.kt`, `android/app/` service + boot receiver + manifest, tests

- [ ] **Step 1: Write the failing test** — `hello` is answered with this device's identity (the Windows side nearly shipped without this); `ping` → `pong`; `list`/`stat`/`pull.request`/`stream.request` behave; clipboard enforces the 64 KB cap and reaches the system clipboard; **an unknown type returns null and is ignored**.

- [ ] **Step 2–4: Implement, test, commit**

`SlipstreamPeer` wires all six modules and binds every socket to the `Network` object from `ConnectivityManager` — spec §11 layer 3, the guarantee that traffic cannot route over cellular even if a route exists. A foreground service with a low-priority persistent notification hosts it, started on `BOOT_COMPLETED` and on launch. Battery-optimisation exemption is requested once with an explanation; the app still works, with slower reconnection, if denied.

`ConnectivityManager.NetworkCallback` drives re-discovery on network change: tear down, re-run all four strategies, resume in-flight transfers from their bitmaps.

---

## Task 13: Cross-implementation conformance against a live Windows peer

The task that proves the whole plan. Everything before this verifies Kotlin against *documents*; this verifies it against the *other implementation*.

- [ ] **Step 1: Pair a real phone with a real PC**

Build and sideload the debug APK. On the PC run `dotnet run --project windows/tools/Slipstream.Harness -- pair-mode pc`. Open the pairing window on the phone.

Expected: both display the **same six digits**. Confirm on both.

- [ ] **Step 2: Walk the matrix and record actual results**

| Check | Expected |
|---|---|
| Phone hotspot, PC joins | PC finds the phone via `gateway-probe`; record elapsed |
| Both on external WiFi | `multicast` wins, or `subnet-sweep` if the AP drops it |
| Repeat on the same network | `cached-endpoint` wins, well under 500 ms |
| PC pulls a 1 GB file from the phone | Completes; hashes match; record MB/s |
| Phone pulls a 1 GB file from the PC | Same |
| Kill WiFi mid-transfer, restore | Resumes from the stopped byte, does not restart |
| Phone picks a video → PC plays it | PC's default player opens; seeking is instant |
| PC browses the phone's DCIM | Thumbnails and durations appear |
| Clipboard both directions | Text arrives and pastes |
| Airplane mode with cellular on | **No traffic leaves the device** — verify with a packet capture or by confirming discovery simply fails |

- [ ] **Step 3: Record throughput honestly**

Note the hotspot figure separately from the router figure. Spec §16 expects the hotspot case to be far slower — 3–5 MB/s against 40–100 MB/s — because the phone runs AP and client duty on one radio. Record what you actually measured; do not tune toward the spec's numbers.

- [ ] **Step 4: Write the deviations record**

`docs/superpowers/plans/2026-08-25-android-core-deviations.md`, following `2026-08-25-core-discovery-control-deviations.md`. Record every place Kotlin forced a difference from the C# design, every vector that needed interpretation, and — plainly — any matrix row you could not run.

- [ ] **Step 5: Commit**

---

## Self-Review

**Spec coverage.** §3 six modules + ports → 1, 2, 12. §4 identity, fingerprint, code, one peer → 3. §4 pin-only TLS → 6. §5 four strategies + coordinator + network change → 5, 12. §5 phone never scans → 5, 12. §6 JSON lines, unknown types ignored → 6, 12. §7 header, CRC32C, bitmap, plan, part file, servers → 8, 9, 10. §8 Range streaming + push-to-play → 11, 12. §9 thumbnails → 11. §10 clipboard → 12. §11 all four layers → 2, 6, 9, 11, 12. §14 foreground service, boot, battery → 12. §16 honest throughput → 13.

**Out of scope:** every screen (Plan 4 consumes this module), and `:meridian-compose` (merged).

**Placeholder scan.** No `TBD`/`TODO`. Tasks 4–7 and 9–12 compress implement/test/commit into single steps where the pattern matches Tasks 2–3 and the C# implementation on `main` is a directly readable reference; each still states exactly what its tests must assert and which vectors gate it.

**Type consistency.** `PeerProbe`, `DiscoveryStrategy`, `LocalNetwork`, `DiscoveredPeer`, `PairingState`, `ChunkBitmap`, `BulkFrameHeader`, and `TransferProgress` mirror their C# counterparts name-for-name so a reader can hold both implementations in mind at once. Where Kotlin idiom differs — `suspend` instead of `Task`, `Sequence` instead of `IEnumerable` — the shape is preserved.

**Known trap, stated once more:** wire integers are **big-endian**; the chunk bitmap's bit order is **little-endian**. Both are pinned by vectors. Getting them backwards is the single likeliest defect in this plan.
