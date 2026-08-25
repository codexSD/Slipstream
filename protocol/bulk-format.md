# Slipstream bulk transfer wire format

Version 1. Port 53322/TCP, plaintext. All multi-byte integers are **big-endian**.

## Stream header — exactly 64 bytes

Sent by the *initiating* side immediately after connecting, before any payload.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | magic | ASCII `SLPS` = `0x53 0x4C 0x50 0x53` |
| 4 | 2 | version | `1` |
| 6 | 2 | streamIndex | 0-based index within this transfer |
| 8 | 16 | token | raw bytes of the single-use bulk token |
| 24 | 16 | transferId | raw bytes |
| 40 | 8 | rangeStart | inclusive byte offset within the file |
| 48 | 8 | rangeLength | number of bytes to transfer |
| 56 | 4 | chunkSize | bytes per chunk; `1048576` in version 1 |
| 60 | 4 | reserved | must be zero; receivers ignore |

A header failing magic, version, or token validation is answered by closing the
socket with no reply. There is no error frame — an unauthenticated peer learns nothing.

## Payload framing

The range is sent as consecutive chunks. For each chunk:

| Size | Field |
|---|---|
| 4 | chunkLength (bytes of data that follow; ≤ chunkSize) |
| n | chunk data |
| 4 | crc32c of the chunk data |

The final chunk of a range may be shorter than `chunkSize`. A chunk whose CRC does
not match is re-requested on a fresh stream; it does not fail the transfer.

`chunkIndex` is not transmitted — it is derived: `(rangeStart / chunkSize) + ordinal`.
Ranges always start on a chunk boundary, which makes this unambiguous.

## Chunk bitmap exchange

Sent over the **control** channel, not this port. A bitmap is a little-endian bit
array, one bit per chunk, bit set = chunk complete and CRC-verified. It is
base64-encoded for JSON transport. Bit *i* corresponds to chunk index *i*.
Trailing bits beyond the file's chunk count are zero and ignored.

Chunk count = `ceil(fileSize / chunkSize)`. Bitmap byte length = `ceil(chunkCount / 8)`.

## Range assignment

- Files **< 4 MB** are assigned whole to a single stream: one range covering `[0, size)`.
- Larger files are split into `min(streamCount, chunkCount)` contiguous ranges, each
  starting on a chunk boundary. Remainder chunks go to the earliest ranges, so range
  lengths differ by at most one chunk.
