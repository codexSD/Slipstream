# Slipstream Core: Discovery & Control — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the headless Windows networking core so two Slipstream instances discover each other on any local network, pair by certificate fingerprint, and exchange control messages over a pinned TLS connection.

**Architecture:** A .NET class library, `Slipstream.Core`, with no UI dependency. Discovery runs four independent strategies concurrently behind a common `PeerProbe` delegate; the first to return a fingerprint-matched peer wins and cancels the rest. Control is a persistent TLS connection carrying newline-delimited JSON. A console harness drives the library for manual verification on real networks.

**Tech Stack:** .NET 9, C# 13, xUnit, `System.Text.Json`, `System.Net.Sockets`, `System.Security.Cryptography.X509Certificates`. No third-party runtime dependencies.

**Spec:** [`docs/superpowers/specs/2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §3 (architecture, ports), §4 (identity and pairing), §5 (discovery), §6 (control channel), §11 (LAN-only enforcement).

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec.

- **Ports:** 53320/UDP discovery, 53321/TCP control (TLS), 53322/TCP bulk, 53323/TCP HTTP media.
- **Multicast group:** `224.0.0.167`, port 53320. Must stay inside `224.0.0.0/24` — some Android devices reject other groups.
- **Protocol version:** `1`. Sent in every announcement and in `hello`.
- **Fingerprint:** SHA-256 of the DER-encoded certificate, lowercase hex, no separators.
- **Pairing code:** 6 decimal digits, derivation order-independent (§4).
- **TLS validation is fingerprint-pin only.** CA chain validation is explicitly disabled. A non-matching certificate is rejected with no prompt and no override path.
- **Exactly one paired peer at a time.** Re-pairing replaces it.
- **LAN-only:** any peer address outside RFC1918 (`10/8`, `172.16/12`, `192.168/16`) or link-local (`169.254/16`, `fe80::/10`) is refused before the handshake, on both inbound and outbound connections.
- **No outbound calls:** no telemetry, analytics, crash reporting, or update checks. No third-party runtime NuGet packages.
- **Subnet sweep is bounded to a /24.** Larger subnets are skipped, never swept.
- **Directory listings cap at 5000 entries** with a `truncated` flag.
- **Unknown control message types are ignored, never fatal.**
- **Language/casing in all user-facing strings:** English, sentence case, no ALL CAPS.

---

## File Structure

```
protocol/
  protocol.md                       # wire spec extracted from the design doc
  vectors/pairing-codes.json        # shared conformance fixtures (Android reads these too)
  vectors/announcements.json
windows/
  Slipstream.sln
  src/Slipstream.Core/
    SlipstreamPorts.cs              # port + multicast constants
    Net/LanGuard.cs                 # RFC1918 / link-local validation
    Net/NetworkInfo.cs              # gateway + local /24 enumeration
    Identity/DeviceIdentity.cs      # device id, self-signed cert, fingerprint
    Identity/Fingerprint.cs         # SHA-256 hex of DER
    Identity/PairingCode.cs         # order-independent 6-digit derivation
    Identity/PairedPeer.cs          # record
    Identity/PairedPeerStore.cs     # single-peer persistence
    Discovery/PeerAnnouncement.cs   # announce/query payload + JSON
    Discovery/PeerProbe.cs          # delegate + PeerIdentity result
    Discovery/EndpointCache.cs      # S1 backing store
    Discovery/IDiscoveryStrategy.cs
    Discovery/CachedEndpointStrategy.cs
    Discovery/GatewayProbeStrategy.cs
    Discovery/MulticastStrategy.cs
    Discovery/SubnetSweepStrategy.cs
    Discovery/DiscoveryCoordinator.cs
    Control/ControlMessage.cs
    Control/JsonLineCodec.cs
    Control/PinnedTls.cs
    Control/ControlConnection.cs
    Control/ControlServer.cs
    Control/ControlClient.cs
  tests/Slipstream.Core.Tests/
  tools/Slipstream.Harness/         # console driver for real-network verification
```

Files split by responsibility, not layer: everything about identity lives together, everything about discovery lives together. Each strategy is its own file so a reviewer can accept or reject one without touching the others.

---

## Task 1: Solution scaffold and CI gate

**Files:**
- Create: `windows/Slipstream.sln`
- Create: `windows/src/Slipstream.Core/Slipstream.Core.csproj`
- Create: `windows/tests/Slipstream.Core.Tests/Slipstream.Core.Tests.csproj`
- Create: `windows/Directory.Build.props`
- Create: `.github/workflows/windows-core.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable solution at `windows/Slipstream.sln`; test command `dotnet test windows/Slipstream.sln`.

- [ ] **Step 1: Create the solution and projects**

```bash
cd windows
dotnet new sln -n Slipstream
dotnet new classlib -n Slipstream.Core -o src/Slipstream.Core -f net9.0
dotnet new xunit -n Slipstream.Core.Tests -o tests/Slipstream.Core.Tests -f net9.0
dotnet sln add src/Slipstream.Core/Slipstream.Core.csproj
dotnet sln add tests/Slipstream.Core.Tests/Slipstream.Core.Tests.csproj
dotnet add tests/Slipstream.Core.Tests/Slipstream.Core.Tests.csproj reference src/Slipstream.Core/Slipstream.Core.csproj
rm src/Slipstream.Core/Class1.cs
```

- [ ] **Step 2: Add shared build settings**

Create `windows/Directory.Build.props`:

```xml
<Project>
  <PropertyGroup>
    <TargetFramework>net9.0</TargetFramework>
    <LangVersion>13.0</LangVersion>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    <EnforceCodeStyleInBuild>true</EnforceCodeStyleInBuild>
  </PropertyGroup>
</Project>
```

`TreatWarningsAsErrors` matters here: nullable warnings in socket code are usually real bugs.

- [ ] **Step 3: Verify it builds and tests run**

Run: `dotnet test windows/Slipstream.sln`
Expected: build succeeds, 0 tests run (or the xunit template's placeholder passes).

- [ ] **Step 4: Add the CI workflow**

Create `.github/workflows/windows-core.yml`:

```yaml
name: windows-core
on:
  push:
    branches: [main]
  pull_request:
jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-dotnet@v4
        with:
          dotnet-version: '9.0.x'
      - run: dotnet test windows/Slipstream.sln --configuration Release
```

- [ ] **Step 5: Commit**

```bash
git add windows .github
git commit -m "chore: scaffold Slipstream.Core solution and CI gate"
```

---

## Task 2: Port constants and LAN-only address guard

The spec's §11 layer-2 guarantee. Written first because every later task depends on it, and because it is the one piece of this plan that is a security control rather than a feature.

**Files:**
- Create: `windows/src/Slipstream.Core/SlipstreamPorts.cs`
- Create: `windows/src/Slipstream.Core/Net/LanGuard.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Net/LanGuardTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static class SlipstreamPorts { const int Discovery = 53320; const int Control = 53321; const int Bulk = 53322; const int Media = 53323; static IPAddress MulticastGroup { get; } }`
  - `static class LanGuard { static bool IsLocal(IPAddress address); static void EnsureLocal(IPAddress address); }`
  - `EnsureLocal` throws `NonLocalAddressException` (also defined in `LanGuard.cs`).

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Net/LanGuardTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Net;

public class LanGuardTests
{
    [Theory]
    [InlineData("10.0.0.1")]
    [InlineData("10.255.255.254")]
    [InlineData("172.16.0.1")]
    [InlineData("172.31.255.254")]
    [InlineData("192.168.1.1")]
    [InlineData("192.168.43.1")]   // the Android hotspot gateway
    [InlineData("169.254.10.20")]  // link-local
    [InlineData("127.0.0.1")]      // loopback, needed for same-machine tests
    [InlineData("::1")]
    [InlineData("fe80::1")]
    public void IsLocal_accepts_private_and_link_local(string address)
    {
        Assert.True(LanGuard.IsLocal(IPAddress.Parse(address)));
    }

    [Theory]
    [InlineData("8.8.8.8")]
    [InlineData("1.1.1.1")]
    [InlineData("172.15.255.255")] // just below the 172.16/12 block
    [InlineData("172.32.0.1")]     // just above it
    [InlineData("192.167.1.1")]    // near-miss on 192.168/16
    [InlineData("11.0.0.1")]       // near-miss on 10/8
    [InlineData("2001:4860:4860::8888")]
    public void IsLocal_rejects_public_addresses(string address)
    {
        Assert.False(LanGuard.IsLocal(IPAddress.Parse(address)));
    }

    [Fact]
    public void EnsureLocal_throws_for_public_address()
    {
        var ex = Assert.Throws<NonLocalAddressException>(
            () => LanGuard.EnsureLocal(IPAddress.Parse("8.8.8.8")));
        Assert.Contains("8.8.8.8", ex.Message);
    }

    [Fact]
    public void EnsureLocal_passes_for_private_address()
    {
        LanGuard.EnsureLocal(IPAddress.Parse("192.168.1.5"));
    }
}
```

The near-miss cases are the point of this test. An off-by-one in the `172.16/12` mask is the classic way this check silently lets public traffic through.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter LanGuardTests`
Expected: FAIL — `LanGuard` and `NonLocalAddressException` do not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/SlipstreamPorts.cs`:

```csharp
using System.Net;

namespace Slipstream.Core;

/// <summary>Fixed ports and groups. See spec §3.</summary>
public static class SlipstreamPorts
{
    public const int Discovery = 53320;
    public const int Control = 53321;
    public const int Bulk = 53322;
    public const int Media = 53323;

    /// <summary>
    /// Constrained to 224.0.0.0/24 — some Android devices reject other groups.
    /// </summary>
    public static readonly IPAddress MulticastGroup = IPAddress.Parse("224.0.0.167");

    public const int ProtocolVersion = 1;
}
```

Create `windows/src/Slipstream.Core/Net/LanGuard.cs`:

```csharp
using System.Net;
using System.Net.Sockets;

namespace Slipstream.Core.Net;

public sealed class NonLocalAddressException(IPAddress address)
    : Exception($"Refused non-local address {address}. Slipstream never leaves the local network.")
{
    public IPAddress Address { get; } = address;
}

/// <summary>
/// Spec §11 layer 2: the only addresses Slipstream will connect to or accept from.
/// Applied to both inbound and outbound connections.
/// </summary>
public static class LanGuard
{
    public static bool IsLocal(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6)
            address = address.MapToIPv4();

        return address.AddressFamily switch
        {
            AddressFamily.InterNetwork => IsLocalV4(address),
            AddressFamily.InterNetworkV6 => IsLocalV6(address),
            _ => false,
        };
    }

    private static bool IsLocalV4(IPAddress address)
    {
        Span<byte> b = stackalloc byte[4];
        if (!address.TryWriteBytes(b, out _)) return false;

        if (b[0] == 10) return true;                          // 10.0.0.0/8
        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true; // 172.16.0.0/12
        if (b[0] == 192 && b[1] == 168) return true;          // 192.168.0.0/16
        if (b[0] == 169 && b[1] == 254) return true;          // 169.254.0.0/16
        if (b[0] == 127) return true;                         // loopback, for tests
        return false;
    }

    private static bool IsLocalV6(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) return true;
        if (address.IsIPv6LinkLocal) return true;             // fe80::/10

        Span<byte> b = stackalloc byte[16];
        if (!address.TryWriteBytes(b, out _)) return false;
        return (b[0] & 0xFE) == 0xFC;                         // fc00::/7 unique-local
    }

    public static void EnsureLocal(IPAddress address)
    {
        if (!IsLocal(address)) throw new NonLocalAddressException(address);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter LanGuardTests`
Expected: PASS, 20 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/SlipstreamPorts.cs windows/src/Slipstream.Core/Net/LanGuard.cs windows/tests/Slipstream.Core.Tests/Net/LanGuardTests.cs
git commit -m "feat: add port constants and LAN-only address guard"
```

---

## Task 3: Device identity — id, self-signed certificate, fingerprint

**Files:**
- Create: `windows/src/Slipstream.Core/Identity/Fingerprint.cs`
- Create: `windows/src/Slipstream.Core/Identity/DeviceIdentity.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Identity/DeviceIdentityTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static class Fingerprint { static string Of(X509Certificate2 cert); }` — lowercase hex SHA-256 of `cert.RawData`.
  - `sealed class DeviceIdentity { string DeviceId { get; } string DisplayName { get; } X509Certificate2 Certificate { get; } string Fingerprint { get; } static DeviceIdentity CreateNew(string displayName); static DeviceIdentity LoadOrCreate(string directory, string displayName); }`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Identity/DeviceIdentityTests.cs`:

```csharp
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Identity;

public class DeviceIdentityTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-id-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    [Fact]
    public void CreateNew_produces_a_128_bit_hex_device_id()
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        Assert.Equal(32, identity.DeviceId.Length);
        Assert.Matches("^[0-9a-f]{32}$", identity.DeviceId);
    }

    [Fact]
    public void CreateNew_produces_distinct_identities()
    {
        Assert.NotEqual(DeviceIdentity.CreateNew("A").DeviceId,
                        DeviceIdentity.CreateNew("B").DeviceId);
    }

    [Fact]
    public void Fingerprint_is_lowercase_hex_sha256_of_the_der()
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        var expected = Convert.ToHexString(SHA256.HashData(identity.Certificate.RawData)).ToLowerInvariant();

        Assert.Equal(expected, identity.Fingerprint);
        Assert.Equal(64, identity.Fingerprint.Length);
        Assert.Matches("^[0-9a-f]{64}$", identity.Fingerprint);
    }

    [Fact]
    public void Certificate_has_a_private_key_usable_for_tls()
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        Assert.True(identity.Certificate.HasPrivateKey);
        Assert.NotNull(identity.Certificate.GetECDsaPrivateKey());
    }

    [Fact]
    public void LoadOrCreate_is_stable_across_calls()
    {
        var first = DeviceIdentity.LoadOrCreate(_dir, "Test PC");
        var second = DeviceIdentity.LoadOrCreate(_dir, "Test PC");

        Assert.Equal(first.DeviceId, second.DeviceId);
        Assert.Equal(first.Fingerprint, second.Fingerprint);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter DeviceIdentityTests`
Expected: FAIL — `DeviceIdentity` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Identity/Fingerprint.cs`:

```csharp
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Slipstream.Core.Identity;

/// <summary>Spec §4: SHA-256 of the DER-encoded certificate, lowercase hex.</summary>
public static class Fingerprint
{
    public static string Of(X509Certificate2 certificate) => Of(certificate.RawData);

    public static string Of(ReadOnlySpan<byte> der) =>
        Convert.ToHexString(SHA256.HashData(der)).ToLowerInvariant();
}
```

Create `windows/src/Slipstream.Core/Identity/DeviceIdentity.cs`:

```csharp
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Slipstream.Core.Identity;

/// <summary>
/// Generated once per install (spec §4). The certificate is self-signed and validated
/// by fingerprint pin only — no CA chain is ever consulted.
/// </summary>
public sealed class DeviceIdentity
{
    private const string CertFileName = "identity.pfx";
    private const string IdFileName = "device-id";

    public string DeviceId { get; }
    public string DisplayName { get; }
    public X509Certificate2 Certificate { get; }
    public string Fingerprint { get; }

    private DeviceIdentity(string deviceId, string displayName, X509Certificate2 certificate)
    {
        DeviceId = deviceId;
        DisplayName = displayName;
        Certificate = certificate;
        Fingerprint = Identity.Fingerprint.Of(certificate);
    }

    public static DeviceIdentity CreateNew(string displayName)
    {
        var deviceId = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        return new DeviceIdentity(deviceId, displayName, CreateCertificate(deviceId));
    }

    public static DeviceIdentity LoadOrCreate(string directory, string displayName)
    {
        Directory.CreateDirectory(directory);
        var certPath = Path.Combine(directory, CertFileName);
        var idPath = Path.Combine(directory, IdFileName);

        if (File.Exists(certPath) && File.Exists(idPath))
        {
            var deviceId = File.ReadAllText(idPath).Trim();
            var certificate = X509CertificateLoader.LoadPkcs12(
                File.ReadAllBytes(certPath),
                password: null,
                keyStorageFlags: X509KeyStorageFlags.Exportable);
            return new DeviceIdentity(deviceId, displayName, certificate);
        }

        var created = CreateNew(displayName);
        File.WriteAllText(idPath, created.DeviceId);
        File.WriteAllBytes(certPath, created.Certificate.Export(X509ContentType.Pkcs12));
        return created;
    }

    private static X509Certificate2 CreateCertificate(string deviceId)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(
            new X500DistinguishedName($"CN=slipstream-{deviceId}"),
            key,
            HashAlgorithmName.SHA256);

        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(certificateAuthority: false, false, 0, critical: true));
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, critical: true));

        // Long-lived: the trust anchor is the pinned fingerprint, not an expiry date.
        var certificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(20));

        // Round-trip through PKCS#12 so the private key is reliably usable by SslStream.
        return X509CertificateLoader.LoadPkcs12(
            certificate.Export(X509ContentType.Pkcs12),
            password: null,
            keyStorageFlags: X509KeyStorageFlags.Exportable);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter DeviceIdentityTests`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Identity windows/tests/Slipstream.Core.Tests/Identity
git commit -m "feat: add device identity with self-signed cert and fingerprint"
```

---

## Task 4: Pairing code derivation and shared conformance vectors

The derivation must be byte-identical on Android, so this task produces the fixture file the Kotlin implementation will be tested against in Plan 3.

**Files:**
- Create: `windows/src/Slipstream.Core/Identity/PairingCode.cs`
- Create: `protocol/vectors/pairing-codes.json`
- Test: `windows/tests/Slipstream.Core.Tests/Identity/PairingCodeTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces: `static class PairingCode { static string Derive(string fingerprintA, string fingerprintB); }` — returns exactly 6 decimal digits, zero-padded.

- [ ] **Step 1: Write the vector fixture**

Create `protocol/vectors/pairing-codes.json`. These fingerprints are fixed test values; the `code` fields are filled in at Step 5 once the implementation exists.

```json
{
  "description": "Pairing code derivation vectors. sorted(a,b) -> concat -> SHA-256 -> first 4 bytes big-endian -> mod 1000000 -> 6 digits.",
  "cases": [
    {
      "a": "0000000000000000000000000000000000000000000000000000000000000000",
      "b": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "code": "PENDING"
    },
    {
      "a": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "b": "0000000000000000000000000000000000000000000000000000000000000000",
      "code": "PENDING"
    },
    {
      "a": "1111111111111111111111111111111111111111111111111111111111111111",
      "b": "2222222222222222222222222222222222222222222222222222222222222222",
      "code": "PENDING"
    }
  ]
}
```

Cases 1 and 2 are the same pair in opposite order and must produce the same code — that is the order-independence requirement from §4.

- [ ] **Step 2: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Identity/PairingCodeTests.cs`:

```csharp
using System.Text.Json;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Identity;

public class PairingCodeTests
{
    private const string A = "0000000000000000000000000000000000000000000000000000000000000000";
    private const string B = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    [Fact]
    public void Derive_is_order_independent()
    {
        Assert.Equal(PairingCode.Derive(A, B), PairingCode.Derive(B, A));
    }

    [Fact]
    public void Derive_returns_exactly_six_digits()
    {
        var code = PairingCode.Derive(A, B);
        Assert.Equal(6, code.Length);
        Assert.Matches("^[0-9]{6}$", code);
    }

    [Fact]
    public void Derive_differs_for_different_pairs()
    {
        const string c = "1111111111111111111111111111111111111111111111111111111111111111";
        Assert.NotEqual(PairingCode.Derive(A, B), PairingCode.Derive(A, c));
    }

    [Fact]
    public void Derive_is_case_insensitive_on_input()
    {
        Assert.Equal(PairingCode.Derive(A, B), PairingCode.Derive(A, B.ToUpperInvariant()));
    }

    [Fact]
    public void Derive_matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(RepoRoot(), "protocol", "vectors", "pairing-codes.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var a = testCase.GetProperty("a").GetString()!;
            var b = testCase.GetProperty("b").GetString()!;
            var expected = testCase.GetProperty("code").GetString()!;

            Assert.NotEqual("PENDING", expected);
            Assert.Equal(expected, PairingCode.Derive(a, b));
        }
    }

    private static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol")))
            dir = dir.Parent;
        return dir?.FullName ?? throw new DirectoryNotFoundException("Could not locate repo root.");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingCodeTests`
Expected: FAIL — `PairingCode` does not exist.

- [ ] **Step 4: Write the implementation**

Create `windows/src/Slipstream.Core/Identity/PairingCode.cs`:

```csharp
using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Slipstream.Core.Identity;

/// <summary>
/// Spec §4. Sorting the two fingerprints before hashing makes the derivation
/// order-independent, so both devices compute the same code without negotiating
/// who is "first".
/// </summary>
public static class PairingCode
{
    public static string Derive(string fingerprintA, string fingerprintB)
    {
        var a = fingerprintA.Trim().ToLowerInvariant();
        var b = fingerprintB.Trim().ToLowerInvariant();

        var (first, second) = string.CompareOrdinal(a, b) <= 0 ? (a, b) : (b, a);

        var digest = SHA256.HashData(Encoding.ASCII.GetBytes(first + second));
        var value = BinaryPrimitives.ReadUInt32BigEndian(digest.AsSpan(0, 4));

        return (value % 1_000_000).ToString("D6");
    }
}
```

- [ ] **Step 5: Fill in the vector file from the implementation**

Run this to print the real codes, then replace each `"PENDING"` in `protocol/vectors/pairing-codes.json` with the printed value:

```bash
cd windows
dotnet run --project tools/Slipstream.Harness -- pairing-vectors 2>/dev/null || \
dotnet script eval 'Slipstream.Core.Identity.PairingCode.Derive("0000000000000000000000000000000000000000000000000000000000000000","ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")'
```

If neither runner is available yet (the harness arrives in Task 16), add a temporary xUnit fact that writes the codes to the test output, run it with `-v n`, copy the values, then delete the fact:

```csharp
[Fact]
public void Print_vectors()
{
    Console.WriteLine(PairingCode.Derive(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
    Console.WriteLine(PairingCode.Derive(
        "1111111111111111111111111111111111111111111111111111111111111111",
        "2222222222222222222222222222222222222222222222222222222222222222"));
}
```

Cases 1 and 2 share a value — they are the same pair reversed.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingCodeTests`
Expected: PASS, 5 tests. The vector test now asserts against real values rather than `PENDING`.

- [ ] **Step 7: Commit**

```bash
git add windows/src/Slipstream.Core/Identity/PairingCode.cs windows/tests/Slipstream.Core.Tests/Identity/PairingCodeTests.cs protocol/vectors/pairing-codes.json
git commit -m "feat: add order-independent pairing code derivation with shared vectors"
```

---

## Task 5: Paired peer store

**Files:**
- Create: `windows/src/Slipstream.Core/Identity/PairedPeer.cs`
- Create: `windows/src/Slipstream.Core/Identity/PairedPeerStore.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Identity/PairedPeerStoreTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed record PairedPeer(string DeviceId, string Fingerprint, string DisplayName, DateTimeOffset PairedAt)`
  - `sealed class PairedPeerStore { PairedPeerStore(string directory); PairedPeer? Current { get; } bool IsPaired { get; } void Pair(PairedPeer peer); void Unpair(); bool Trusts(string fingerprint); }`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Identity/PairedPeerStoreTests.cs`:

```csharp
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Identity;

public class PairedPeerStoreTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-peer-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static PairedPeer Peer(string id = "abc123", string fp = "deadbeef") =>
        new(id, fp, "Test Phone", DateTimeOffset.UtcNow);

    [Fact]
    public void A_new_store_is_unpaired()
    {
        var store = new PairedPeerStore(_dir);
        Assert.False(store.IsPaired);
        Assert.Null(store.Current);
    }

    [Fact]
    public void Pair_persists_across_instances()
    {
        new PairedPeerStore(_dir).Pair(Peer());

        var reloaded = new PairedPeerStore(_dir);
        Assert.True(reloaded.IsPaired);
        Assert.Equal("abc123", reloaded.Current!.DeviceId);
        Assert.Equal("Test Phone", reloaded.Current.DisplayName);
    }

    [Fact]
    public void Pairing_again_replaces_the_existing_peer()
    {
        var store = new PairedPeerStore(_dir);
        store.Pair(Peer("first", "aaaa"));
        store.Pair(Peer("second", "bbbb"));

        Assert.Equal("second", store.Current!.DeviceId);
        Assert.False(store.Trusts("aaaa"));
        Assert.True(store.Trusts("bbbb"));
    }

    [Fact]
    public void Trusts_is_case_insensitive_and_false_when_unpaired()
    {
        var store = new PairedPeerStore(_dir);
        Assert.False(store.Trusts("deadbeef"));

        store.Pair(Peer(fp: "deadbeef"));
        Assert.True(store.Trusts("DEADBEEF"));
        Assert.False(store.Trusts("cafebabe"));
    }

    [Fact]
    public void Unpair_clears_the_store()
    {
        var store = new PairedPeerStore(_dir);
        store.Pair(Peer());
        store.Unpair();

        Assert.False(store.IsPaired);
        Assert.False(new PairedPeerStore(_dir).IsPaired);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairedPeerStoreTests`
Expected: FAIL — `PairedPeerStore` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Identity/PairedPeer.cs`:

```csharp
namespace Slipstream.Core.Identity;

public sealed record PairedPeer(
    string DeviceId,
    string Fingerprint,
    string DisplayName,
    DateTimeOffset PairedAt);
```

Create `windows/src/Slipstream.Core/Identity/PairedPeerStore.cs`:

```csharp
using System.Text.Json;

namespace Slipstream.Core.Identity;

/// <summary>
/// Spec §4: exactly one paired peer at a time. Re-pairing replaces it.
/// </summary>
public sealed class PairedPeerStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly string _path;
    private PairedPeer? _current;

    public PairedPeerStore(string directory)
    {
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "paired-peer.json");

        if (File.Exists(_path))
        {
            try
            {
                _current = JsonSerializer.Deserialize<PairedPeer>(File.ReadAllText(_path), Json);
            }
            catch (JsonException)
            {
                // A corrupt store means unpaired, not a crash. The user re-pairs.
                _current = null;
            }
        }
    }

    public PairedPeer? Current => _current;

    public bool IsPaired => _current is not null;

    public void Pair(PairedPeer peer)
    {
        _current = peer;
        File.WriteAllText(_path, JsonSerializer.Serialize(peer, Json));
    }

    public void Unpair()
    {
        _current = null;
        if (File.Exists(_path)) File.Delete(_path);
    }

    public bool Trusts(string fingerprint) =>
        _current is not null &&
        string.Equals(_current.Fingerprint, fingerprint, StringComparison.OrdinalIgnoreCase);
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairedPeerStoreTests`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Identity/PairedPeer.cs windows/src/Slipstream.Core/Identity/PairedPeerStore.cs windows/tests/Slipstream.Core.Tests/Identity/PairedPeerStoreTests.cs
git commit -m "feat: add single-peer pairing store"
```

---

## Task 6: Peer announcement payload and codec

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/PeerAnnouncement.cs`
- Create: `protocol/vectors/announcements.json`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/PeerAnnouncementTests.cs`

**Interfaces:**
- Consumes: `SlipstreamPorts.ProtocolVersion`.
- Produces:
  - `enum AnnouncementKind { Announce, Query }`
  - `sealed record PeerAnnouncement(int Version, string DeviceId, string DisplayName, string Fingerprint, int ControlPort, AnnouncementKind Kind)`
  - `byte[] ToBytes()` and `static PeerAnnouncement? TryParse(ReadOnlySpan<byte> utf8)` — returns `null` on malformed input rather than throwing, because this parses untrusted network data.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/PeerAnnouncementTests.cs`:

```csharp
using System.Text;
using Slipstream.Core;
using Slipstream.Core.Discovery;

namespace Slipstream.Core.Tests.Discovery;

public class PeerAnnouncementTests
{
    private static PeerAnnouncement Sample() => new(
        Version: SlipstreamPorts.ProtocolVersion,
        DeviceId: "abc123",
        DisplayName: "Test PC",
        Fingerprint: "deadbeef",
        ControlPort: SlipstreamPorts.Control,
        Kind: AnnouncementKind.Announce);

    [Fact]
    public void Round_trips_through_bytes()
    {
        var parsed = PeerAnnouncement.TryParse(Sample().ToBytes());

        Assert.NotNull(parsed);
        Assert.Equal(Sample(), parsed);
    }

    [Fact]
    public void Serialises_kind_as_a_lowercase_string()
    {
        var json = Encoding.UTF8.GetString(Sample().ToBytes());
        Assert.Contains("\"kind\":\"announce\"", json);
        Assert.Contains("\"v\":1", json);
    }

    [Theory]
    [InlineData("")]
    [InlineData("not json at all")]
    [InlineData("{}")]
    [InlineData("{\"v\":1}")]
    [InlineData("{\"v\":1,\"deviceId\":\"a\"}")]
    public void TryParse_returns_null_for_malformed_input(string payload)
    {
        Assert.Null(PeerAnnouncement.TryParse(Encoding.UTF8.GetBytes(payload)));
    }

    [Fact]
    public void TryParse_returns_null_for_a_future_protocol_version()
    {
        var future = Sample() with { Version = 99 };
        Assert.Null(PeerAnnouncement.TryParse(future.ToBytes()));
    }

    [Fact]
    public void Payload_stays_within_a_single_datagram()
    {
        var large = Sample() with { DisplayName = new string('x', 200) };
        Assert.True(large.ToBytes().Length < 1024);
    }
}
```

The datagram-size test is deliberate: an oversized announcement fragments and is silently dropped by some access points.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PeerAnnouncementTests`
Expected: FAIL — `PeerAnnouncement` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/PeerAnnouncement.cs`:

```csharp
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slipstream.Core.Discovery;

[JsonConverter(typeof(JsonStringEnumConverter<AnnouncementKind>))]
public enum AnnouncementKind
{
    [JsonStringEnumMemberName("announce")] Announce,
    [JsonStringEnumMemberName("query")] Query,
}

/// <summary>
/// Spec §5 S3. Sent to the multicast group and, as a fallback, unicast back to a
/// querying peer for networks that deliver multicast in one direction only.
/// </summary>
public sealed record PeerAnnouncement(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("name")] string DisplayName,
    [property: JsonPropertyName("fingerprint")] string Fingerprint,
    [property: JsonPropertyName("control")] int ControlPort,
    [property: JsonPropertyName("kind")] AnnouncementKind Kind)
{
    private static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public byte[] ToBytes() => JsonSerializer.SerializeToUtf8Bytes(this, Options);

    /// <summary>Returns null on anything malformed — this parses untrusted network data.</summary>
    public static PeerAnnouncement? TryParse(ReadOnlySpan<byte> utf8)
    {
        try
        {
            var parsed = JsonSerializer.Deserialize<PeerAnnouncement>(utf8, Options);

            if (parsed is null) return null;
            if (parsed.Version != SlipstreamPorts.ProtocolVersion) return null;
            if (string.IsNullOrWhiteSpace(parsed.DeviceId)) return null;
            if (string.IsNullOrWhiteSpace(parsed.Fingerprint)) return null;
            if (parsed.ControlPort is <= 0 or > 65535) return null;

            return parsed;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PeerAnnouncementTests`
Expected: PASS, 9 tests.

- [ ] **Step 5: Record a wire sample in the vectors folder**

Create `protocol/vectors/announcements.json` with the exact bytes the Kotlin implementation must produce:

```json
{
  "description": "Canonical announcement payloads. Field names and ordering are normative.",
  "cases": [
    {
      "name": "announce",
      "json": "{\"v\":1,\"deviceId\":\"abc123\",\"name\":\"Test PC\",\"fingerprint\":\"deadbeef\",\"control\":53321,\"kind\":\"announce\"}"
    },
    {
      "name": "query",
      "json": "{\"v\":1,\"deviceId\":\"abc123\",\"name\":\"Test PC\",\"fingerprint\":\"deadbeef\",\"control\":53321,\"kind\":\"query\"}"
    }
  ]
}
```

- [ ] **Step 6: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/PeerAnnouncement.cs windows/tests/Slipstream.Core.Tests/Discovery/PeerAnnouncementTests.cs protocol/vectors/announcements.json
git commit -m "feat: add peer announcement payload and tolerant codec"
```

---

## Task 7: Peer probe contract and endpoint cache

The four strategies all need the same question answered — "is our paired peer at this address?" — and all need somewhere to record the answer. Defining both here lets Tasks 9–12 be written and tested against a fake, before the real TLS probe exists in Task 14.

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/PeerProbe.cs`
- Create: `windows/src/Slipstream.Core/Discovery/EndpointCache.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/EndpointCacheTests.cs`

**Interfaces:**
- Consumes: `PairedPeer`.
- Produces:
  - `sealed record DiscoveredPeer(PairedPeer Peer, IPEndPoint Endpoint)`
  - `delegate Task<DiscoveredPeer?> PeerProbe(IPEndPoint endpoint, CancellationToken cancellationToken)` — returns `null` for "not our peer", never throws for an unreachable host.
  - `sealed class EndpointCache { EndpointCache(string directory); IPEndPoint? Get(string networkKey); void Set(string networkKey, IPEndPoint endpoint); void Clear(); }`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/EndpointCacheTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Discovery;

namespace Slipstream.Core.Tests.Discovery;

public class EndpointCacheTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-cache-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    [Fact]
    public void Get_returns_null_for_an_unknown_network()
    {
        Assert.Null(new EndpointCache(_dir).Get("unknown-ssid"));
    }

    [Fact]
    public void Set_then_Get_round_trips_and_persists()
    {
        var endpoint = new IPEndPoint(IPAddress.Parse("192.168.43.1"), 53321);
        new EndpointCache(_dir).Set("home-wifi", endpoint);

        Assert.Equal(endpoint, new EndpointCache(_dir).Get("home-wifi"));
    }

    [Fact]
    public void Entries_are_keyed_per_network()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("hotspot", new IPEndPoint(IPAddress.Parse("192.168.43.1"), 53321));
        cache.Set("cafe", new IPEndPoint(IPAddress.Parse("10.0.0.7"), 53321));

        Assert.Equal(IPAddress.Parse("192.168.43.1"), cache.Get("hotspot")!.Address);
        Assert.Equal(IPAddress.Parse("10.0.0.7"), cache.Get("cafe")!.Address);
    }

    [Fact]
    public void Set_overwrites_an_existing_entry()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("wifi", new IPEndPoint(IPAddress.Parse("10.0.0.1"), 53321));
        cache.Set("wifi", new IPEndPoint(IPAddress.Parse("10.0.0.2"), 53321));

        Assert.Equal(IPAddress.Parse("10.0.0.2"), cache.Get("wifi")!.Address);
    }

    [Fact]
    public void Clear_empties_the_cache()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("wifi", new IPEndPoint(IPAddress.Loopback, 53321));
        cache.Clear();

        Assert.Null(cache.Get("wifi"));
        Assert.Null(new EndpointCache(_dir).Get("wifi"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter EndpointCacheTests`
Expected: FAIL — `EndpointCache` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/PeerProbe.cs`:

```csharp
using System.Net;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Discovery;

public sealed record DiscoveredPeer(PairedPeer Peer, IPEndPoint Endpoint);

/// <summary>
/// Answers "is our paired peer at this endpoint?". Returns null for no, and for any
/// unreachable host — an unreachable address is an expected outcome during a sweep,
/// not an error. Implemented for real in Task 14.
/// </summary>
public delegate Task<DiscoveredPeer?> PeerProbe(IPEndPoint endpoint, CancellationToken cancellationToken);
```

Create `windows/src/Slipstream.Core/Discovery/EndpointCache.cs`:

```csharp
using System.Collections.Concurrent;
using System.Net;
using System.Text.Json;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S1. Keyed per network so the hotspot address and the home-WiFi address
/// do not overwrite each other.
/// </summary>
public sealed class EndpointCache
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly string _path;
    private readonly ConcurrentDictionary<string, string> _entries;

    public EndpointCache(string directory)
    {
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "endpoint-cache.json");

        Dictionary<string, string>? loaded = null;
        if (File.Exists(_path))
        {
            try
            {
                loaded = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(_path), Json);
            }
            catch (JsonException)
            {
                loaded = null; // A corrupt cache is a cold cache, not a crash.
            }
        }

        _entries = new ConcurrentDictionary<string, string>(
            loaded ?? new Dictionary<string, string>(), StringComparer.OrdinalIgnoreCase);
    }

    public IPEndPoint? Get(string networkKey) =>
        _entries.TryGetValue(networkKey, out var value) && IPEndPoint.TryParse(value, out var endpoint)
            ? endpoint
            : null;

    public void Set(string networkKey, IPEndPoint endpoint)
    {
        _entries[networkKey] = endpoint.ToString();
        Save();
    }

    public void Clear()
    {
        _entries.Clear();
        Save();
    }

    private void Save() =>
        File.WriteAllText(_path, JsonSerializer.Serialize(
            new Dictionary<string, string>(_entries), Json));
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter EndpointCacheTests`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/PeerProbe.cs windows/src/Slipstream.Core/Discovery/EndpointCache.cs windows/tests/Slipstream.Core.Tests/Discovery/EndpointCacheTests.cs
git commit -m "feat: add peer probe contract and per-network endpoint cache"
```

---

## Task 8: Network information — gateway, network key, local /24

**Files:**
- Create: `windows/src/Slipstream.Core/Net/NetworkInfo.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Net/NetworkInfoTests.cs`

**Interfaces:**
- Consumes: `LanGuard`.
- Produces:
  - `sealed record LocalNetwork(IPAddress LocalAddress, IPAddress? Gateway, int PrefixLength, string Key)`
  - `interface INetworkInfo { LocalNetwork? Current(); }`
  - `sealed class NetworkInfo : INetworkInfo`
  - `static class SubnetMath { static IEnumerable<IPAddress> EnumerateHosts(IPAddress address, int prefixLength); }` — yields nothing for a prefix shorter than /24.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Net/NetworkInfoTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Net;

public class SubnetMathTests
{
    [Fact]
    public void Enumerates_254_hosts_for_a_slash_24()
    {
        var hosts = SubnetMath.EnumerateHosts(IPAddress.Parse("192.168.1.37"), 24).ToList();

        Assert.Equal(254, hosts.Count);
        Assert.Equal(IPAddress.Parse("192.168.1.1"), hosts[0]);
        Assert.Equal(IPAddress.Parse("192.168.1.254"), hosts[^1]);
    }

    [Fact]
    public void Excludes_the_network_and_broadcast_addresses()
    {
        var hosts = SubnetMath.EnumerateHosts(IPAddress.Parse("192.168.1.37"), 24).ToList();

        Assert.DoesNotContain(IPAddress.Parse("192.168.1.0"), hosts);
        Assert.DoesNotContain(IPAddress.Parse("192.168.1.255"), hosts);
    }

    [Fact]
    public void Refuses_to_sweep_anything_wider_than_a_slash_24()
    {
        Assert.Empty(SubnetMath.EnumerateHosts(IPAddress.Parse("10.0.0.1"), 16));
        Assert.Empty(SubnetMath.EnumerateHosts(IPAddress.Parse("10.0.0.1"), 8));
    }

    [Fact]
    public void Handles_a_prefix_narrower_than_a_slash_24()
    {
        var hosts = SubnetMath.EnumerateHosts(IPAddress.Parse("192.168.1.130"), 25).ToList();

        Assert.Equal(126, hosts.Count);
        Assert.Equal(IPAddress.Parse("192.168.1.129"), hosts[0]);
        Assert.Equal(IPAddress.Parse("192.168.1.254"), hosts[^1]);
    }
}

public class NetworkInfoTests
{
    [Fact]
    public void Current_returns_a_local_address_or_null_when_offline()
    {
        var current = new NetworkInfo().Current();

        if (current is null) return; // CI runners are sometimes networkless.

        Assert.True(LanGuard.IsLocal(current.LocalAddress));
        Assert.False(string.IsNullOrWhiteSpace(current.Key));
        if (current.Gateway is not null) Assert.True(LanGuard.IsLocal(current.Gateway));
    }
}
```

`EnumerateHosts` refusing anything wider than /24 is the Global Constraint made executable — it is the guard that stops a `/16` sweep from firing 65 000 sockets.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter "SubnetMathTests|NetworkInfoTests"`
Expected: FAIL — `SubnetMath` and `NetworkInfo` do not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Net/NetworkInfo.cs`:

```csharp
using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Slipstream.Core.Net;

/// <summary>
/// The active local network. <paramref name="Key"/> is the cache key used by
/// discovery strategy S1 — stable for a given network, distinct across networks.
/// </summary>
public sealed record LocalNetwork(
    IPAddress LocalAddress,
    IPAddress? Gateway,
    int PrefixLength,
    string Key);

public interface INetworkInfo
{
    LocalNetwork? Current();
}

public sealed class NetworkInfo : INetworkInfo
{
    public LocalNetwork? Current()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            var properties = nic.GetIPProperties();

            var unicast = properties.UnicastAddresses.FirstOrDefault(a =>
                a.Address.AddressFamily == AddressFamily.InterNetwork &&
                LanGuard.IsLocal(a.Address) &&
                !IPAddress.IsLoopback(a.Address));

            if (unicast is null) continue;

            var gateway = properties.GatewayAddresses
                .Select(g => g.Address)
                .FirstOrDefault(a =>
                    a is not null &&
                    a.AddressFamily == AddressFamily.InterNetwork &&
                    !a.Equals(IPAddress.Any) &&
                    LanGuard.IsLocal(a));

            var prefix = unicast.PrefixLength is > 0 and <= 32 ? unicast.PrefixLength : 24;

            // Keyed on the interface plus the subnet, so moving between networks on the
            // same adapter produces a different key.
            var key = $"{nic.Id}|{MaskToNetwork(unicast.Address, prefix)}/{prefix}";

            return new LocalNetwork(unicast.Address, gateway, prefix, key);
        }

        return null;
    }

    private static IPAddress MaskToNetwork(IPAddress address, int prefixLength)
    {
        Span<byte> bytes = stackalloc byte[4];
        address.TryWriteBytes(bytes, out _);

        var value = BinaryPrimitives.ReadUInt32BigEndian(bytes);
        var mask = prefixLength == 0 ? 0u : uint.MaxValue << (32 - prefixLength);
        BinaryPrimitives.WriteUInt32BigEndian(bytes, value & mask);

        return new IPAddress(bytes);
    }
}

public static class SubnetMath
{
    /// <summary>
    /// Host addresses in the subnet, excluding network and broadcast. Yields nothing for
    /// anything wider than a /24 — spec §5 S4 bounds the sweep, and 65 000 concurrent
    /// sockets is a denial of service against your own machine.
    /// </summary>
    public static IEnumerable<IPAddress> EnumerateHosts(IPAddress address, int prefixLength)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork) yield break;
        if (prefixLength is < 24 or > 30) yield break;

        var bytes = new byte[4];
        address.TryWriteBytes(bytes, out _);

        var value = BinaryPrimitives.ReadUInt32BigEndian(bytes);
        var mask = uint.MaxValue << (32 - prefixLength);
        var network = value & mask;
        var broadcast = network | ~mask;

        for (var host = network + 1; host < broadcast; host++)
        {
            var buffer = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(buffer, host);
            yield return new IPAddress(buffer);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter "SubnetMathTests|NetworkInfoTests"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Net/NetworkInfo.cs windows/tests/Slipstream.Core.Tests/Net/NetworkInfoTests.cs
git commit -m "feat: add network info and bounded subnet enumeration"
```

---

## Task 9: Strategy contract, shared test fake, and S1 cached endpoint

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/IDiscoveryStrategy.cs`
- Create: `windows/src/Slipstream.Core/Discovery/CachedEndpointStrategy.cs`
- Create: `windows/tests/Slipstream.Core.Tests/Discovery/FakeProbe.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/CachedEndpointStrategyTests.cs`

**Interfaces:**
- Consumes: `PeerProbe`, `DiscoveredPeer`, `EndpointCache`, `LocalNetwork`, `INetworkInfo`.
- Produces:
  - `interface IDiscoveryStrategy { string Name { get; } Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken); }`
  - `sealed class CachedEndpointStrategy : IDiscoveryStrategy` with constructor `(EndpointCache cache, PeerProbe probe)`.
  - `FakeProbe` test helper: `FakeProbe(params string[] respondingEndpoints)`, exposing `PeerProbe Probe { get; }`, `IReadOnlyList<IPEndPoint> Attempts { get; }`, and `int Delay { get; set; }` (milliseconds).

- [ ] **Step 1: Write the shared test fake**

Create `windows/tests/Slipstream.Core.Tests/Discovery/FakeProbe.cs`. Every strategy test uses this, which is why it is a file rather than a nested class:

```csharp
using System.Collections.Concurrent;
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Discovery;

/// <summary>
/// A PeerProbe that answers for a fixed set of endpoints and records every attempt.
/// </summary>
public sealed class FakeProbe
{
    public static readonly PairedPeer Peer =
        new("peer-device", "deadbeef", "Test Phone", DateTimeOffset.UnixEpoch);

    private readonly HashSet<string> _responding;
    private readonly ConcurrentQueue<IPEndPoint> _attempts = new();

    public FakeProbe(params string[] respondingEndpoints) =>
        _responding = new HashSet<string>(respondingEndpoints, StringComparer.OrdinalIgnoreCase);

    /// <summary>Milliseconds each probe takes before answering. Used to test racing.</summary>
    public int Delay { get; set; }

    public IReadOnlyList<IPEndPoint> Attempts => _attempts.ToList();

    public PeerProbe Probe => async (endpoint, cancellationToken) =>
    {
        _attempts.Enqueue(endpoint);

        if (Delay > 0) await Task.Delay(Delay, cancellationToken);

        return _responding.Contains(endpoint.ToString())
            ? new DiscoveredPeer(Peer, endpoint)
            : null;
    };
}
```

- [ ] **Step 2: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/CachedEndpointStrategyTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class CachedEndpointStrategyTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-s1-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static LocalNetwork Network(string key = "test-net") =>
        new(IPAddress.Parse("192.168.1.50"), IPAddress.Parse("192.168.1.1"), 24, key);

    [Fact]
    public async Task Returns_null_when_nothing_is_cached()
    {
        var probe = new FakeProbe("192.168.1.9:53321");
        var strategy = new CachedEndpointStrategy(new EndpointCache(_dir), probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Probes_the_cached_endpoint_and_returns_the_peer()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("test-net", new IPEndPoint(IPAddress.Parse("192.168.1.9"), 53321));

        var probe = new FakeProbe("192.168.1.9:53321");
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        var found = await strategy.FindAsync(Network(), CancellationToken.None);

        Assert.NotNull(found);
        Assert.Equal("192.168.1.9", found.Endpoint.Address.ToString());
        Assert.Single(probe.Attempts);
    }

    [Fact]
    public async Task Returns_null_when_the_cached_endpoint_no_longer_answers()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("test-net", new IPEndPoint(IPAddress.Parse("192.168.1.9"), 53321));

        var probe = new FakeProbe(); // nothing responds
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
    }

    [Fact]
    public async Task Only_uses_the_entry_for_the_current_network()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("other-net", new IPEndPoint(IPAddress.Parse("192.168.1.9"), 53321));

        var probe = new FakeProbe("192.168.1.9:53321");
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        Assert.Null(await strategy.FindAsync(Network("test-net"), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Skips_a_cached_endpoint_that_is_not_a_local_address()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("test-net", new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53321));

        var probe = new FakeProbe("8.8.8.8:53321");
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }
}
```

The last test matters: a cache file is on-disk state an attacker or a bug could corrupt, so the LAN guard is re-applied on read rather than trusted from write time.

- [ ] **Step 3: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter CachedEndpointStrategyTests`
Expected: FAIL — `IDiscoveryStrategy` and `CachedEndpointStrategy` do not exist.

- [ ] **Step 4: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/IDiscoveryStrategy.cs`:

```csharp
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// One way of locating the paired peer. Strategies never throw for "not found" —
/// they return null, so the coordinator can race them without exception handling.
/// </summary>
public interface IDiscoveryStrategy
{
    string Name { get; }

    Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken);
}
```

Create `windows/src/Slipstream.Core/Discovery/CachedEndpointStrategy.cs`:

```csharp
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S1. The common case: both target networks are stable, so the last known
/// address is usually still correct and answers in about 50 ms.
/// </summary>
public sealed class CachedEndpointStrategy(EndpointCache cache, PeerProbe probe) : IDiscoveryStrategy
{
    public string Name => "cached-endpoint";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var endpoint = cache.Get(network.Key);
        if (endpoint is null) return null;

        // Re-validate on read: the cache is on-disk state, not a trusted source.
        if (!LanGuard.IsLocal(endpoint.Address)) return null;

        return await probe(endpoint, cancellationToken);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter CachedEndpointStrategyTests`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/IDiscoveryStrategy.cs windows/src/Slipstream.Core/Discovery/CachedEndpointStrategy.cs windows/tests/Slipstream.Core.Tests/Discovery/FakeProbe.cs windows/tests/Slipstream.Core.Tests/Discovery/CachedEndpointStrategyTests.cs
git commit -m "feat: add discovery strategy contract and cached endpoint strategy"
```

---

## Task 10: S2 gateway probe

The decisive strategy for the primary scenario. When the phone is the hotspot it *is* the gateway, so this resolves deterministically with no scanning and no dependence on access-point behaviour.

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/GatewayProbeStrategy.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/GatewayProbeStrategyTests.cs`

**Interfaces:**
- Consumes: `IDiscoveryStrategy`, `PeerProbe`, `LocalNetwork`, `LanGuard`.
- Produces: `sealed class GatewayProbeStrategy : IDiscoveryStrategy` with constructor `(PeerProbe probe)`.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/GatewayProbeStrategyTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class GatewayProbeStrategyTests
{
    private static LocalNetwork Network(string? gateway) =>
        new(IPAddress.Parse("192.168.43.100"),
            gateway is null ? null : IPAddress.Parse(gateway),
            24,
            "hotspot");

    [Fact]
    public async Task Finds_the_peer_at_the_gateway_address()
    {
        // The Android hotspot case: the phone is the gateway.
        var probe = new FakeProbe("192.168.43.1:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        var found = await strategy.FindAsync(Network("192.168.43.1"), CancellationToken.None);

        Assert.NotNull(found);
        Assert.Equal("192.168.43.1", found.Endpoint.Address.ToString());
        Assert.Equal(53321, found.Endpoint.Port);
    }

    [Fact]
    public async Task Probes_exactly_once()
    {
        var probe = new FakeProbe("192.168.43.1:53321");
        await new GatewayProbeStrategy(probe.Probe).FindAsync(Network("192.168.43.1"), CancellationToken.None);

        Assert.Single(probe.Attempts);
    }

    [Fact]
    public async Task Returns_null_when_there_is_no_gateway()
    {
        var probe = new FakeProbe("192.168.43.1:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(gateway: null), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Returns_null_when_the_gateway_is_a_router_rather_than_the_peer()
    {
        // External WiFi: the gateway is a router that does not speak Slipstream.
        var probe = new FakeProbe("10.0.0.7:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network("10.0.0.1"), CancellationToken.None));
    }

    [Fact]
    public async Task Refuses_a_non_local_gateway()
    {
        var probe = new FakeProbe("8.8.8.8:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network("8.8.8.8"), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter GatewayProbeStrategyTests`
Expected: FAIL — `GatewayProbeStrategy` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/GatewayProbeStrategy.cs`:

```csharp
using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S2. Decisive for the phone-hotspot case, where the phone is the PC's
/// default gateway: one probe, no scanning, no multicast, cannot be defeated by
/// access-point behaviour.
/// </summary>
public sealed class GatewayProbeStrategy(PeerProbe probe) : IDiscoveryStrategy
{
    public string Name => "gateway-probe";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        if (network.Gateway is null) return null;
        if (!LanGuard.IsLocal(network.Gateway)) return null;

        return await probe(new IPEndPoint(network.Gateway, SlipstreamPorts.Control), cancellationToken);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter GatewayProbeStrategyTests`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/GatewayProbeStrategy.cs windows/tests/Slipstream.Core.Tests/Discovery/GatewayProbeStrategyTests.cs
git commit -m "feat: add gateway probe discovery strategy"
```

---

## Task 11: S3 multicast announce and listen

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/MulticastStrategy.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/MulticastStrategyTests.cs`

**Interfaces:**
- Consumes: `IDiscoveryStrategy`, `PeerProbe`, `PeerAnnouncement`, `PairedPeerStore`, `DeviceIdentity`, `SlipstreamPorts`.
- Produces: `sealed class MulticastStrategy : IDiscoveryStrategy, IAsyncDisposable` with constructor `(DeviceIdentity identity, PairedPeerStore peers, PeerProbe probe, int listenPort = SlipstreamPorts.Discovery)`. Also exposes `Task RespondToQueriesAsync(CancellationToken)` — the always-on responder the server runs.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/MulticastStrategyTests.cs`. These use a real loopback UDP socket on an ephemeral port so the codec and socket wiring are genuinely exercised without needing a second machine:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class MulticastStrategyTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-s3-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static LocalNetwork Network() =>
        new(IPAddress.Loopback, IPAddress.Parse("127.0.0.1"), 24, "loopback");

    private (DeviceIdentity Identity, PairedPeerStore Peers) Paired(string peerFingerprint)
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        var peers = new PairedPeerStore(_dir);
        peers.Pair(new PairedPeer("peer-device", peerFingerprint, "Test Phone", DateTimeOffset.UnixEpoch));
        return (identity, peers);
    }

    [Fact]
    public async Task Returns_null_when_unpaired()
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        var peers = new PairedPeerStore(_dir); // never paired
        var probe = new FakeProbe();

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        Assert.Null(await strategy.FindAsync(Network(), cts.Token));
    }

    [Fact]
    public async Task Probes_a_peer_that_announces_with_the_trusted_fingerprint()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var find = strategy.FindAsync(Network(), cts.Token);

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, "peer-device", "Test Phone",
            "deadbeef", SlipstreamPorts.Control, AnnouncementKind.Announce));

        var found = await find;

        Assert.NotNull(found);
        Assert.Equal(53321, found.Endpoint.Port);
    }

    [Fact]
    public async Task Ignores_an_announcement_from_an_untrusted_fingerprint()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var find = strategy.FindAsync(Network(), cts.Token);

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, "stranger", "Someone Else",
            "cafebabe", SlipstreamPorts.Control, AnnouncementKind.Announce));

        Assert.Null(await find);
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Ignores_its_own_announcement()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var find = strategy.FindAsync(Network(), cts.Token);

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, identity.DeviceId, identity.DisplayName,
            identity.Fingerprint, SlipstreamPorts.Control, AnnouncementKind.Announce));

        Assert.Null(await find);
    }

    [Fact]
    public async Task Ignores_malformed_datagrams_without_failing()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var find = strategy.FindAsync(Network(), cts.Token);

        using (var junk = new UdpClient(AddressFamily.InterNetwork))
        {
            await junk.SendAsync("garbage"u8.ToArray(), strategy.ListenEndPoint);
        }

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, "peer-device", "Test Phone",
            "deadbeef", SlipstreamPorts.Control, AnnouncementKind.Announce));

        Assert.NotNull(await find);
    }

    private static async Task SendUnicastAsync(IPEndPoint target, PeerAnnouncement announcement)
    {
        await Task.Delay(200); // let the listener bind
        using var sender = new UdpClient(AddressFamily.InterNetwork);
        await sender.SendAsync(announcement.ToBytes(), target);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter MulticastStrategyTests`
Expected: FAIL — `MulticastStrategy` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/MulticastStrategy.cs`:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S3. Announces to the multicast group and listens for the paired peer.
/// A peer that receives a query replies by unicast, which is the fallback for
/// networks that deliver multicast in one direction only.
/// </summary>
public sealed class MulticastStrategy : IDiscoveryStrategy, IAsyncDisposable
{
    private static readonly TimeSpan AnnounceInterval = TimeSpan.FromMilliseconds(700);

    private readonly DeviceIdentity _identity;
    private readonly PairedPeerStore _peers;
    private readonly PeerProbe _probe;
    private readonly UdpClient _listener;

    public MulticastStrategy(
        DeviceIdentity identity,
        PairedPeerStore peers,
        PeerProbe probe,
        int listenPort = SlipstreamPorts.Discovery)
    {
        _identity = identity;
        _peers = peers;
        _probe = probe;

        _listener = new UdpClient(AddressFamily.InterNetwork);
        _listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _listener.Client.Bind(new IPEndPoint(IPAddress.Any, listenPort));

        TryJoinMulticastGroup();
    }

    /// <summary>The bound listening endpoint. Tests use this to send directly.</summary>
    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.Client.LocalEndPoint!;

    public string Name => "multicast";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var peer = _peers.Current;
        if (peer is null) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        var announcing = AnnounceRepeatedlyAsync(AnnouncementKind.Query, linked.Token);

        try
        {
            while (!linked.Token.IsCancellationRequested)
            {
                var received = await _listener.ReceiveAsync(linked.Token);

                var announcement = PeerAnnouncement.TryParse(received.Buffer);
                if (announcement is null) continue;

                // Never discover ourselves.
                if (string.Equals(announcement.Fingerprint, _identity.Fingerprint, StringComparison.OrdinalIgnoreCase))
                    continue;

                if (!_peers.Trusts(announcement.Fingerprint)) continue;
                if (!LanGuard.IsLocal(received.RemoteEndPoint.Address)) continue;

                var endpoint = new IPEndPoint(received.RemoteEndPoint.Address, announcement.ControlPort);

                var found = await _probe(endpoint, linked.Token);
                if (found is not null) return found;
            }
        }
        catch (OperationCanceledException)
        {
            // Cancelled by the coordinator because another strategy won, or by timeout.
        }
        finally
        {
            await linked.CancelAsync();
            await SwallowAsync(announcing);
        }

        return null;
    }

    /// <summary>
    /// The always-on responder: reply by unicast to any query from the paired peer.
    /// Run by the server for the lifetime of the app.
    /// </summary>
    public async Task RespondToQueriesAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var received = await _listener.ReceiveAsync(cancellationToken);

                var announcement = PeerAnnouncement.TryParse(received.Buffer);
                if (announcement is null) continue;
                if (announcement.Kind != AnnouncementKind.Query) continue;
                if (!_peers.Trusts(announcement.Fingerprint)) continue;
                if (!LanGuard.IsLocal(received.RemoteEndPoint.Address)) continue;

                await _listener.SendAsync(Payload(AnnouncementKind.Announce), received.RemoteEndPoint, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                // Transient; keep listening.
            }
        }
    }

    private async Task AnnounceRepeatedlyAsync(AnnouncementKind kind, CancellationToken cancellationToken)
    {
        var group = new IPEndPoint(SlipstreamPorts.MulticastGroup, SlipstreamPorts.Discovery);
        var payload = Payload(kind);

        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await _listener.SendAsync(payload, group, cancellationToken);
                await Task.Delay(AnnounceInterval, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                // Multicast send fails on some adapters. That is exactly why S4 exists.
                await Task.Delay(AnnounceInterval, CancellationToken.None);
            }
        }
    }

    private byte[] Payload(AnnouncementKind kind) => new PeerAnnouncement(
        SlipstreamPorts.ProtocolVersion,
        _identity.DeviceId,
        _identity.DisplayName,
        _identity.Fingerprint,
        SlipstreamPorts.Control,
        kind).ToBytes();

    private void TryJoinMulticastGroup()
    {
        try
        {
            _listener.JoinMulticastGroup(SlipstreamPorts.MulticastGroup);
        }
        catch (SocketException)
        {
            // Some adapters refuse the join. Unicast replies still work; S4 covers the rest.
        }
    }

    private static async Task SwallowAsync(Task task)
    {
        try { await task; } catch (OperationCanceledException) { }
    }

    public ValueTask DisposeAsync()
    {
        _listener.Dispose();
        return ValueTask.CompletedTask;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter MulticastStrategyTests`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/MulticastStrategy.cs windows/tests/Slipstream.Core.Tests/Discovery/MulticastStrategyTests.cs
git commit -m "feat: add multicast discovery strategy with unicast query responder"
```

---

## Task 12: S4 parallel subnet sweep

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/SubnetSweepStrategy.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/SubnetSweepStrategyTests.cs`

**Interfaces:**
- Consumes: `IDiscoveryStrategy`, `PeerProbe`, `SubnetMath`, `LocalNetwork`.
- Produces: `sealed class SubnetSweepStrategy : IDiscoveryStrategy` with constructor `(PeerProbe probe, int maxConcurrency = 254)`.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/SubnetSweepStrategyTests.cs`:

```csharp
using System.Diagnostics;
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class SubnetSweepStrategyTests
{
    private static LocalNetwork Network(int prefixLength = 24) =>
        new(IPAddress.Parse("192.168.1.50"), IPAddress.Parse("192.168.1.1"), prefixLength, "test-net");

    [Fact]
    public async Task Finds_a_peer_anywhere_in_the_subnet()
    {
        var probe = new FakeProbe("192.168.1.200:53321");
        var strategy = new SubnetSweepStrategy(probe.Probe);

        var found = await strategy.FindAsync(Network(), CancellationToken.None);

        Assert.NotNull(found);
        Assert.Equal("192.168.1.200", found.Endpoint.Address.ToString());
    }

    [Fact]
    public async Task Returns_null_when_nothing_answers()
    {
        var probe = new FakeProbe();
        var strategy = new SubnetSweepStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
        Assert.Equal(254, probe.Attempts.Count);
    }

    [Fact]
    public async Task Refuses_to_sweep_a_subnet_wider_than_a_slash_24()
    {
        var probe = new FakeProbe("10.5.5.5:53321");
        var strategy = new SubnetSweepStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(prefixLength: 16), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Runs_probes_concurrently_rather_than_serially()
    {
        var probe = new FakeProbe("192.168.1.254:53321") { Delay = 50 };
        var strategy = new SubnetSweepStrategy(probe.Probe);

        var stopwatch = Stopwatch.StartNew();
        var found = await strategy.FindAsync(Network(), CancellationToken.None);
        stopwatch.Stop();

        Assert.NotNull(found);
        // Serial would be 254 * 50ms = 12.7s. Concurrent should finish in well under 2s.
        Assert.True(stopwatch.ElapsedMilliseconds < 2000,
            $"Sweep took {stopwatch.ElapsedMilliseconds}ms — probes are not running concurrently.");
    }

    [Fact]
    public async Task Stops_early_when_cancelled()
    {
        var probe = new FakeProbe { Delay = 200 };
        var strategy = new SubnetSweepStrategy(probe.Probe);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));

        Assert.Null(await strategy.FindAsync(Network(), cts.Token));
    }
}
```

The concurrency test is the reason this task exists as its own unit: a sweep that accidentally runs serially still passes every correctness test and simply takes thirteen seconds instead of one.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter SubnetSweepStrategyTests`
Expected: FAIL — `SubnetSweepStrategy` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/SubnetSweepStrategy.cs`:

```csharp
using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S4. The backstop for access points that silently drop multicast.
/// Bounded to a /24 by SubnetMath — a wider sweep is refused, not attempted.
/// </summary>
public sealed class SubnetSweepStrategy(PeerProbe probe, int maxConcurrency = 254) : IDiscoveryStrategy
{
    public string Name => "subnet-sweep";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var hosts = SubnetMath
            .EnumerateHosts(network.LocalAddress, network.PrefixLength)
            .Where(host => !host.Equals(network.LocalAddress))
            .ToList();

        if (hosts.Count == 0) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        using var slots = new SemaphoreSlim(maxConcurrency);

        DiscoveredPeer? winner = null;

        var probes = hosts.Select(async host =>
        {
            await slots.WaitAsync(linked.Token);
            try
            {
                var found = await probe(new IPEndPoint(host, SlipstreamPorts.Control), linked.Token);
                if (found is not null)
                {
                    Interlocked.CompareExchange(ref winner, found, null);
                    await linked.CancelAsync();
                }
            }
            catch (OperationCanceledException)
            {
                // Another probe won, or the caller cancelled.
            }
            finally
            {
                slots.Release();
            }
        });

        try
        {
            await Task.WhenAll(probes);
        }
        catch (OperationCanceledException)
        {
            // Expected once a winner cancels the rest.
        }

        return winner;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter SubnetSweepStrategyTests`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/SubnetSweepStrategy.cs windows/tests/Slipstream.Core.Tests/Discovery/SubnetSweepStrategyTests.cs
git commit -m "feat: add bounded parallel subnet sweep strategy"
```

---

## Task 13: Discovery coordinator — race the four strategies

**Files:**
- Create: `windows/src/Slipstream.Core/Discovery/DiscoveryCoordinator.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Discovery/DiscoveryCoordinatorTests.cs`

**Interfaces:**
- Consumes: `IDiscoveryStrategy`, `INetworkInfo`, `EndpointCache`, `DiscoveredPeer`.
- Produces:
  - `sealed record DiscoveryResult(DiscoveredPeer Peer, string StrategyName, TimeSpan Elapsed)`
  - `sealed class DiscoveryCoordinator { DiscoveryCoordinator(INetworkInfo networkInfo, EndpointCache cache, IReadOnlyList<IDiscoveryStrategy> strategies); Task<DiscoveryResult?> DiscoverAsync(TimeSpan timeout, CancellationToken cancellationToken); }`
  - On success the winning endpoint is written to the cache under the current network key.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Discovery/DiscoveryCoordinatorTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class DiscoveryCoordinatorTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-coord-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private sealed class StubNetworkInfo(LocalNetwork? network) : INetworkInfo
    {
        public LocalNetwork? Current() => network;
    }

    private sealed class StubStrategy(string name, int delayMs, DiscoveredPeer? result) : IDiscoveryStrategy
    {
        public string Name => name;
        public bool WasCancelled { get; private set; }

        public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
        {
            try
            {
                await Task.Delay(delayMs, cancellationToken);
                return result;
            }
            catch (OperationCanceledException)
            {
                WasCancelled = true;
                throw;
            }
        }
    }

    private sealed class ThrowingStrategy : IDiscoveryStrategy
    {
        public string Name => "throwing";
        public Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken) =>
            throw new SocketException(10013);
    }

    private static LocalNetwork Network() =>
        new(IPAddress.Parse("192.168.1.50"), IPAddress.Parse("192.168.1.1"), 24, "test-net");

    private static DiscoveredPeer Peer(string address) =>
        new(FakeProbe.Peer, new IPEndPoint(IPAddress.Parse(address), 53321));

    [Fact]
    public async Task Returns_the_fastest_strategys_result()
    {
        var fast = new StubStrategy("fast", 50, Peer("192.168.1.9"));
        var slow = new StubStrategy("slow", 3000, Peer("192.168.1.10"));

        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir), [slow, fast]);

        var result = await coordinator.DiscoverAsync(TimeSpan.FromSeconds(10), CancellationToken.None);

        Assert.NotNull(result);
        Assert.Equal("fast", result.StrategyName);
        Assert.Equal("192.168.1.9", result.Peer.Endpoint.Address.ToString());
    }

    [Fact]
    public async Task Cancels_the_losing_strategies()
    {
        var fast = new StubStrategy("fast", 50, Peer("192.168.1.9"));
        var slow = new StubStrategy("slow", 5000, Peer("192.168.1.10"));

        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir), [slow, fast]);

        await coordinator.DiscoverAsync(TimeSpan.FromSeconds(10), CancellationToken.None);

        Assert.True(slow.WasCancelled);
    }

    [Fact]
    public async Task Caches_the_winning_endpoint_under_the_network_key()
    {
        var cache = new EndpointCache(_dir);
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), cache,
            [new StubStrategy("fast", 10, Peer("192.168.1.9"))]);

        await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None);

        Assert.Equal(IPAddress.Parse("192.168.1.9"), cache.Get("test-net")!.Address);
    }

    [Fact]
    public async Task Returns_null_when_every_strategy_finds_nothing()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir),
            [new StubStrategy("a", 10, null), new StubStrategy("b", 20, null)]);

        Assert.Null(await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None));
    }

    [Fact]
    public async Task A_throwing_strategy_does_not_prevent_another_from_winning()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir),
            [new ThrowingStrategy(), new StubStrategy("good", 50, Peer("192.168.1.9"))]);

        var result = await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None);

        Assert.NotNull(result);
        Assert.Equal("good", result.StrategyName);
    }

    [Fact]
    public async Task Returns_null_when_offline()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(null), new EndpointCache(_dir),
            [new StubStrategy("fast", 10, Peer("192.168.1.9"))]);

        Assert.Null(await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None));
    }

    [Fact]
    public async Task Returns_null_when_the_timeout_expires()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir),
            [new StubStrategy("slow", 10000, Peer("192.168.1.9"))]);

        Assert.Null(await coordinator.DiscoverAsync(TimeSpan.FromMilliseconds(200), CancellationToken.None));
    }
}
```

The throwing-strategy test encodes a real failure mode: on a machine where multicast is blocked by firewall policy the socket call raises, and a naive `Task.WhenAny` would surface that exception instead of letting the sweep win.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter DiscoveryCoordinatorTests`
Expected: FAIL — `DiscoveryCoordinator` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Discovery/DiscoveryCoordinator.cs`:

```csharp
using System.Diagnostics;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

public sealed record DiscoveryResult(DiscoveredPeer Peer, string StrategyName, TimeSpan Elapsed);

/// <summary>
/// Spec §5. Runs every strategy concurrently; the first to return a fingerprint-matched
/// peer wins and the rest are cancelled. A strategy that throws is treated as "found
/// nothing" — a blocked multicast socket must not prevent the sweep from winning.
/// </summary>
public sealed class DiscoveryCoordinator(
    INetworkInfo networkInfo,
    EndpointCache cache,
    IReadOnlyList<IDiscoveryStrategy> strategies)
{
    public async Task<DiscoveryResult?> DiscoverAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var network = networkInfo.Current();
        if (network is null) return null;
        if (strategies.Count == 0) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        var stopwatch = Stopwatch.StartNew();

        var running = strategies
            .Select(strategy => RunAsync(strategy, network, stopwatch, linked.Token))
            .ToList();

        try
        {
            while (running.Count > 0)
            {
                var completed = await Task.WhenAny(running);
                running.Remove(completed);

                var result = await completed;
                if (result is null) continue;

                await linked.CancelAsync();
                cache.Set(network.Key, result.Peer.Endpoint);
                return result;
            }
        }
        finally
        {
            await linked.CancelAsync();
            await Task.WhenAll(running.Select(SwallowAsync));
        }

        return null;
    }

    private static async Task<DiscoveryResult?> RunAsync(
        IDiscoveryStrategy strategy,
        LocalNetwork network,
        Stopwatch stopwatch,
        CancellationToken cancellationToken)
    {
        try
        {
            var peer = await strategy.FindAsync(network, cancellationToken);
            return peer is null ? null : new DiscoveryResult(peer, strategy.Name, stopwatch.Elapsed);
        }
        catch (Exception)
        {
            // A failing strategy is a strategy that found nothing. Never let one
            // adapter's firewall policy take down discovery as a whole.
            return null;
        }
    }

    private static async Task SwallowAsync(Task task)
    {
        try { await task; } catch { /* losers are cancelled by design */ }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter DiscoveryCoordinatorTests`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Discovery/DiscoveryCoordinator.cs windows/tests/Slipstream.Core.Tests/Discovery/DiscoveryCoordinatorTests.cs
git commit -m "feat: race discovery strategies and cache the winning endpoint"
```

---

## Task 14: Control messages and newline-delimited JSON codec

**Files:**
- Create: `windows/src/Slipstream.Core/Control/ControlMessage.cs`
- Create: `windows/src/Slipstream.Core/Control/JsonLineCodec.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Control/JsonLineCodecTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed class ControlMessage { string Type { get; init; } string? Id { get; init; } JsonElement? Payload { get; init; } static ControlMessage Request(string type, string id, object? payload = null); static ControlMessage Event(string type, object? payload = null); T? PayloadAs<T>(); }`
  - `sealed class JsonLineCodec { JsonLineCodec(Stream stream); Task WriteAsync(ControlMessage message, CancellationToken ct); Task<ControlMessage?> ReadAsync(CancellationToken ct); }` — returns `null` at end of stream; throws `ControlProtocolException` on an over-long line.
  - `const int MaxLineBytes = 1_048_576`.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Control/JsonLineCodecTests.cs`:

```csharp
using System.Text;
using Slipstream.Core.Control;

namespace Slipstream.Core.Tests.Control;

public class JsonLineCodecTests
{
    private static MemoryStream StreamOf(string text) =>
        new(Encoding.UTF8.GetBytes(text));

    [Fact]
    public async Task Round_trips_a_request_with_a_payload()
    {
        using var buffer = new MemoryStream();
        var writer = new JsonLineCodec(buffer);

        await writer.WriteAsync(
            ControlMessage.Request("list", "7f3a", new { path = "/DCIM", sort = "name" }),
            CancellationToken.None);

        buffer.Position = 0;
        var message = await new JsonLineCodec(buffer).ReadAsync(CancellationToken.None);

        Assert.NotNull(message);
        Assert.Equal("list", message.Type);
        Assert.Equal("7f3a", message.Id);
        Assert.Equal("/DCIM", message.Payload!.Value.GetProperty("path").GetString());
    }

    [Fact]
    public async Task Writes_one_message_per_line()
    {
        using var buffer = new MemoryStream();
        var codec = new JsonLineCodec(buffer);

        await codec.WriteAsync(ControlMessage.Event("ping"), CancellationToken.None);
        await codec.WriteAsync(ControlMessage.Event("pong"), CancellationToken.None);

        var text = Encoding.UTF8.GetString(buffer.ToArray());
        var lines = text.Split('\n', StringSplitOptions.RemoveEmptyEntries);

        Assert.Equal(2, lines.Length);
        Assert.DoesNotContain('\n', lines[0]);
    }

    [Fact]
    public async Task Reads_multiple_messages_in_sequence()
    {
        using var stream = StreamOf("{\"type\":\"ping\"}\n{\"type\":\"pong\"}\n");
        var codec = new JsonLineCodec(stream);

        Assert.Equal("ping", (await codec.ReadAsync(CancellationToken.None))!.Type);
        Assert.Equal("pong", (await codec.ReadAsync(CancellationToken.None))!.Type);
        Assert.Null(await codec.ReadAsync(CancellationToken.None));
    }

    [Fact]
    public async Task Events_carry_no_id()
    {
        using var buffer = new MemoryStream();
        await new JsonLineCodec(buffer).WriteAsync(
            ControlMessage.Event("transfer.progress", new { bytes = 42 }), CancellationToken.None);

        var text = Encoding.UTF8.GetString(buffer.ToArray());
        Assert.DoesNotContain("\"id\"", text);
    }

    [Fact]
    public async Task Skips_blank_lines()
    {
        using var stream = StreamOf("\n\n{\"type\":\"ping\"}\n");
        Assert.Equal("ping", (await new JsonLineCodec(stream).ReadAsync(CancellationToken.None))!.Type);
    }

    [Fact]
    public async Task Skips_a_malformed_line_rather_than_failing_the_connection()
    {
        using var stream = StreamOf("not json\n{\"type\":\"ping\"}\n");
        Assert.Equal("ping", (await new JsonLineCodec(stream).ReadAsync(CancellationToken.None))!.Type);
    }

    [Fact]
    public async Task Skips_a_line_with_no_type_field()
    {
        using var stream = StreamOf("{\"id\":\"a\"}\n{\"type\":\"ping\"}\n");
        Assert.Equal("ping", (await new JsonLineCodec(stream).ReadAsync(CancellationToken.None))!.Type);
    }

    [Fact]
    public async Task Rejects_an_over_long_line()
    {
        var oversized = new string('x', JsonLineCodec.MaxLineBytes + 10);
        using var stream = StreamOf($"{{\"type\":\"{oversized}\"}}\n");

        await Assert.ThrowsAsync<ControlProtocolException>(
            () => new JsonLineCodec(stream).ReadAsync(CancellationToken.None));
    }

    [Fact]
    public async Task PayloadAs_deserialises_into_a_typed_record()
    {
        using var buffer = new MemoryStream();
        await new JsonLineCodec(buffer).WriteAsync(
            ControlMessage.Request("hello", "1", new HelloPayload("abc", "Test PC", 1)),
            CancellationToken.None);

        buffer.Position = 0;
        var message = await new JsonLineCodec(buffer).ReadAsync(CancellationToken.None);

        var payload = message!.PayloadAs<HelloPayload>();
        Assert.Equal("abc", payload!.DeviceId);
        Assert.Equal(1, payload.Version);
    }

    private sealed record HelloPayload(string DeviceId, string Name, int Version);
}
```

Skipping a malformed line rather than dropping the connection is the Global Constraint "unknown message types are ignored, never fatal" applied at the framing layer — a peer on a newer protocol version must degrade, not disconnect.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter JsonLineCodecTests`
Expected: FAIL — `JsonLineCodec` and `ControlMessage` do not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Control/ControlMessage.cs`:

```csharp
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slipstream.Core.Control;

public sealed class ControlProtocolException(string message) : Exception(message);

/// <summary>
/// Spec §6. Requests carry an id; responses echo it; events carry none.
/// </summary>
public sealed class ControlMessage
{
    internal static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    [JsonPropertyName("type")]
    public required string Type { get; init; }

    [JsonPropertyName("id")]
    public string? Id { get; init; }

    [JsonPropertyName("payload")]
    public JsonElement? Payload { get; init; }

    public static ControlMessage Request(string type, string id, object? payload = null) =>
        new() { Type = type, Id = id, Payload = ToElement(payload) };

    public static ControlMessage Response(string type, string id, object? payload = null) =>
        Request(type, id, payload);

    public static ControlMessage Event(string type, object? payload = null) =>
        new() { Type = type, Payload = ToElement(payload) };

    public T? PayloadAs<T>() =>
        Payload is null ? default : Payload.Value.Deserialize<T>(Json);

    private static JsonElement? ToElement(object? payload) =>
        payload is null ? null : JsonSerializer.SerializeToElement(payload, Json);
}
```

Create `windows/src/Slipstream.Core/Control/JsonLineCodec.cs`:

```csharp
using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Slipstream.Core.Control;

/// <summary>
/// Spec §6 framing: one UTF-8 JSON object per newline-terminated line.
/// Malformed lines are skipped, never fatal — a peer on a newer protocol
/// version must degrade rather than disconnect.
/// </summary>
public sealed class JsonLineCodec(Stream stream)
{
    public const int MaxLineBytes = 1_048_576;

    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly ArrayBufferWriter<byte> _line = new(4096);

    public async Task WriteAsync(ControlMessage message, CancellationToken cancellationToken)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(message, ControlMessage.Json);

        if (bytes.Length + 1 > MaxLineBytes)
            throw new ControlProtocolException($"Outgoing message of {bytes.Length} bytes exceeds the line limit.");

        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            await stream.WriteAsync(bytes, cancellationToken);
            await stream.WriteAsync("\n"u8.ToArray(), cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>Returns null at end of stream.</summary>
    public async Task<ControlMessage?> ReadAsync(CancellationToken cancellationToken)
    {
        while (true)
        {
            var line = await ReadLineAsync(cancellationToken);
            if (line is null) return null;
            if (line.Length == 0) continue;

            var message = TryParse(line);
            if (message is not null) return message;
            // Malformed or type-less: skip and keep reading.
        }
    }

    private async Task<byte[]?> ReadLineAsync(CancellationToken cancellationToken)
    {
        _line.Clear();
        var single = new byte[1];

        while (true)
        {
            var read = await stream.ReadAsync(single, cancellationToken);
            if (read == 0) return _line.WrittenCount == 0 ? null : _line.WrittenSpan.ToArray();

            if (single[0] == (byte)'\n') return _line.WrittenSpan.ToArray();
            if (single[0] == (byte)'\r') continue;

            if (_line.WrittenCount >= MaxLineBytes)
                throw new ControlProtocolException($"Incoming line exceeded {MaxLineBytes} bytes.");

            _line.Write(single);
        }
    }

    private static ControlMessage? TryParse(byte[] line)
    {
        try
        {
            var message = JsonSerializer.Deserialize<ControlMessage>(line, ControlMessage.Json);
            return string.IsNullOrWhiteSpace(message?.Type) ? null : message;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
```

Reading a byte at a time is deliberate for the control channel: messages are small and infrequent, and a buffered reader would need to hand back its over-read bytes when the bulk path later takes over a socket. Throughput on this channel is irrelevant — throughput lives on 53322.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter JsonLineCodecTests`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Control windows/tests/Slipstream.Core.Tests/Control
git commit -m "feat: add control message model and newline-delimited JSON codec"
```

---

## Task 15: Fingerprint-pinned TLS, control connection, and the real peer probe

This is where the pieces converge: the probe that Tasks 9–13 have been testing against a fake becomes real.

**Files:**
- Create: `windows/src/Slipstream.Core/Control/PinnedTls.cs`
- Create: `windows/src/Slipstream.Core/Control/ControlConnection.cs`
- Create: `windows/src/Slipstream.Core/Control/ControlClient.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Control/PinnedTlsTests.cs`

**Interfaces:**
- Consumes: `DeviceIdentity`, `PairedPeerStore`, `JsonLineCodec`, `ControlMessage`, `LanGuard`, `PeerProbe`.
- Produces:
  - `static class PinnedTls { static Task<SslStream> AuthenticateAsClientAsync(Stream inner, DeviceIdentity identity, Func<string, bool> acceptFingerprint, CancellationToken ct); static Task<SslStream> AuthenticateAsServerAsync(Stream inner, DeviceIdentity identity, CancellationToken ct); static string FingerprintOf(SslStream stream); }`
  - `sealed class ControlConnection : IAsyncDisposable { string PeerFingerprint { get; } IPEndPoint RemoteEndPoint { get; } Task SendAsync(ControlMessage, CancellationToken); Task<ControlMessage?> ReceiveAsync(CancellationToken); }`
  - `sealed class ControlClient { ControlClient(DeviceIdentity identity, PairedPeerStore peers); PeerProbe CreateProbe(TimeSpan timeout); Task<ControlConnection?> ConnectAsync(IPEndPoint endpoint, TimeSpan timeout, CancellationToken ct); }`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Control/PinnedTlsTests.cs`:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Control;

public class PinnedTlsTests
{
    /// <summary>Starts a TLS server on loopback and returns its endpoint.</summary>
    private static (TcpListener Listener, Task Serving) StartServer(
        DeviceIdentity identity,
        Func<ControlConnection, Task> handle,
        CancellationToken cancellationToken)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();

        var serving = Task.Run(async () =>
        {
            try
            {
                using var client = await listener.AcceptTcpClientAsync(cancellationToken);
                var stream = await PinnedTls.AuthenticateAsServerAsync(
                    client.GetStream(), identity, cancellationToken);

                await using var connection = new ControlConnection(
                    stream, PinnedTls.FingerprintOf(stream),
                    (IPEndPoint)client.Client.RemoteEndPoint!);

                await handle(connection);
            }
            catch (Exception) { /* the test asserts on the client side */ }
        }, cancellationToken);

        return (listener, serving);
    }

    [Fact]
    public async Task Client_connects_when_the_server_fingerprint_is_trusted()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));

        var serverIdentity = DeviceIdentity.CreateNew("Server");
        var clientIdentity = DeviceIdentity.CreateNew("Client");

        var (listener, _) = StartServer(serverIdentity, async connection =>
        {
            await connection.SendAsync(ControlMessage.Event("hello.ok"), cts.Token);
        }, cts.Token);

        try
        {
            var endpoint = (IPEndPoint)listener.LocalEndpoint;

            using var tcp = new TcpClient();
            await tcp.ConnectAsync(endpoint, cts.Token);

            await using var stream = await PinnedTls.AuthenticateAsClientAsync(
                tcp.GetStream(), clientIdentity,
                fingerprint => fingerprint == serverIdentity.Fingerprint, cts.Token);

            Assert.Equal(serverIdentity.Fingerprint, PinnedTls.FingerprintOf(stream));

            var codec = new JsonLineCodec(stream);
            var message = await codec.ReadAsync(cts.Token);
            Assert.Equal("hello.ok", message!.Type);
        }
        finally
        {
            listener.Stop();
        }
    }

    [Fact]
    public async Task Client_refuses_a_server_whose_fingerprint_is_not_pinned()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));

        var serverIdentity = DeviceIdentity.CreateNew("Server");
        var clientIdentity = DeviceIdentity.CreateNew("Client");

        var (listener, _) = StartServer(serverIdentity, _ => Task.CompletedTask, cts.Token);

        try
        {
            using var tcp = new TcpClient();
            await tcp.ConnectAsync((IPEndPoint)listener.LocalEndpoint, cts.Token);

            await Assert.ThrowsAnyAsync<Exception>(() =>
                PinnedTls.AuthenticateAsClientAsync(
                    tcp.GetStream(), clientIdentity,
                    _ => false, // trust nothing
                    cts.Token));
        }
        finally
        {
            listener.Stop();
        }
    }

    [Fact]
    public async Task Probe_returns_the_peer_for_a_trusted_endpoint()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var dir = Directory.CreateTempSubdirectory("slipstream-probe-").FullName;

        try
        {
            var serverIdentity = DeviceIdentity.CreateNew("Server");
            var clientIdentity = DeviceIdentity.CreateNew("Client");

            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("server-device", serverIdentity.Fingerprint, "Server", DateTimeOffset.UnixEpoch));

            var (listener, _) = StartServer(serverIdentity, async connection =>
            {
                await connection.ReceiveAsync(cts.Token);
                await Task.Delay(Timeout.Infinite, cts.Token);
            }, cts.Token);

            try
            {
                var probe = new ControlClient(clientIdentity, peers).CreateProbe(TimeSpan.FromSeconds(5));
                var found = await probe((IPEndPoint)listener.LocalEndpoint, cts.Token);

                Assert.NotNull(found);
                Assert.Equal("server-device", found.Peer.DeviceId);
            }
            finally
            {
                listener.Stop();
            }
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Probe_returns_null_for_an_untrusted_endpoint()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var dir = Directory.CreateTempSubdirectory("slipstream-probe2-").FullName;

        try
        {
            var serverIdentity = DeviceIdentity.CreateNew("Server");
            var clientIdentity = DeviceIdentity.CreateNew("Client");

            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("other", "not-the-server-fingerprint", "Other", DateTimeOffset.UnixEpoch));

            var (listener, _) = StartServer(serverIdentity, _ => Task.CompletedTask, cts.Token);

            try
            {
                var probe = new ControlClient(clientIdentity, peers).CreateProbe(TimeSpan.FromSeconds(5));
                Assert.Null(await probe((IPEndPoint)listener.LocalEndpoint, cts.Token));
            }
            finally
            {
                listener.Stop();
            }
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Probe_returns_null_for_a_closed_port_without_throwing()
    {
        var dir = Directory.CreateTempSubdirectory("slipstream-probe3-").FullName;

        try
        {
            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("x", "abcd", "X", DateTimeOffset.UnixEpoch));

            var probe = new ControlClient(DeviceIdentity.CreateNew("Client"), peers)
                .CreateProbe(TimeSpan.FromMilliseconds(500));

            // Port 1 on loopback: nothing listening.
            Assert.Null(await probe(new IPEndPoint(IPAddress.Loopback, 1), CancellationToken.None));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Probe_refuses_a_non_local_endpoint_without_connecting()
    {
        var dir = Directory.CreateTempSubdirectory("slipstream-probe4-").FullName;

        try
        {
            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("x", "abcd", "X", DateTimeOffset.UnixEpoch));

            var probe = new ControlClient(DeviceIdentity.CreateNew("Client"), peers)
                .CreateProbe(TimeSpan.FromSeconds(5));

            Assert.Null(await probe(new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53321), CancellationToken.None));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }
}
```

The last two tests are the ones that keep discovery usable: a probe that throws on a closed port would make the 254-way sweep a storm of exceptions, and a probe that dials a public address would breach §11.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PinnedTlsTests`
Expected: FAIL — `PinnedTls`, `ControlConnection`, and `ControlClient` do not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Control/PinnedTls.cs`:

```csharp
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Control;

/// <summary>
/// Spec §4: validation is fingerprint-pin only. CA chain validation is meaningless
/// for self-signed local certificates and is explicitly disabled. There is no
/// prompt and no override path for a non-matching certificate.
/// </summary>
public static class PinnedTls
{
    public static async Task<SslStream> AuthenticateAsClientAsync(
        Stream inner,
        DeviceIdentity identity,
        Func<string, bool> acceptFingerprint,
        CancellationToken cancellationToken)
    {
        var stream = new SslStream(inner, leaveInnerStreamOpen: false,
            userCertificateValidationCallback: (_, certificate, _, _) =>
                certificate is not null && acceptFingerprint(Fingerprint.Of(certificate.GetRawCertData())));

        await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "slipstream",
            ClientCertificates = [identity.Certificate],
            EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                certificate is not null && acceptFingerprint(Fingerprint.Of(certificate.GetRawCertData())),
        }, cancellationToken);

        return stream;
    }

    public static async Task<SslStream> AuthenticateAsServerAsync(
        Stream inner,
        DeviceIdentity identity,
        CancellationToken cancellationToken)
    {
        // The server accepts any client certificate at the TLS layer; the caller
        // checks the resulting fingerprint against the paired peer afterwards.
        var stream = new SslStream(inner, leaveInnerStreamOpen: false,
            userCertificateValidationCallback: (_, _, _, _) => true);

        await stream.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
        {
            ServerCertificate = identity.Certificate,
            ClientCertificateRequired = true,
            EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = (_, _, _, _) => true,
        }, cancellationToken);

        return stream;
    }

    /// <summary>The remote certificate's fingerprint, for pin comparison.</summary>
    public static string FingerprintOf(SslStream stream)
    {
        var certificate = stream.RemoteCertificate
            ?? throw new ControlProtocolException("The peer presented no certificate.");

        return Fingerprint.Of(certificate.GetRawCertData());
    }
}
```

Create `windows/src/Slipstream.Core/Control/ControlConnection.cs`:

```csharp
using System.Net;

namespace Slipstream.Core.Control;

/// <summary>Spec §6: one persistent TLS connection carrying JSON lines.</summary>
public sealed class ControlConnection(Stream stream, string peerFingerprint, IPEndPoint remoteEndPoint)
    : IAsyncDisposable
{
    private readonly JsonLineCodec _codec = new(stream);

    public string PeerFingerprint { get; } = peerFingerprint;

    public IPEndPoint RemoteEndPoint { get; } = remoteEndPoint;

    public Task SendAsync(ControlMessage message, CancellationToken cancellationToken) =>
        _codec.WriteAsync(message, cancellationToken);

    public Task<ControlMessage?> ReceiveAsync(CancellationToken cancellationToken) =>
        _codec.ReadAsync(cancellationToken);

    public async ValueTask DisposeAsync() => await stream.DisposeAsync();
}
```

Create `windows/src/Slipstream.Core/Control/ControlClient.cs`:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Control;

/// <summary>
/// Outbound half of the control channel, and the source of the real PeerProbe that
/// discovery strategies consume.
/// </summary>
public sealed class ControlClient(DeviceIdentity identity, PairedPeerStore peers)
{
    /// <summary>
    /// A probe that answers "is our paired peer here?" and never throws for an
    /// unreachable host — during a 254-way sweep, unreachable is the normal answer.
    /// </summary>
    public PeerProbe CreateProbe(TimeSpan timeout) => async (endpoint, cancellationToken) =>
    {
        var peer = peers.Current;
        if (peer is null) return null;
        if (!LanGuard.IsLocal(endpoint.Address)) return null;

        try
        {
            await using var connection = await ConnectAsync(endpoint, timeout, cancellationToken);
            return connection is null ? null : new DiscoveredPeer(peer, endpoint);
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            return null;
        }
    };

    /// <summary>
    /// Connects and completes a fingerprint-pinned handshake. Returns null when the
    /// peer is reachable but is not our paired peer.
    /// </summary>
    public async Task<ControlConnection?> ConnectAsync(
        IPEndPoint endpoint,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        LanGuard.EnsureLocal(endpoint.Address);

        if (!peers.IsPaired) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        var tcp = new TcpClient();
        try
        {
            tcp.NoDelay = true;
            await tcp.ConnectAsync(endpoint, linked.Token);

            var stream = await PinnedTls.AuthenticateAsClientAsync(
                tcp.GetStream(), identity, peers.Trusts, linked.Token);

            return new ControlConnection(stream, PinnedTls.FingerprintOf(stream), endpoint);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PinnedTlsTests`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the whole suite to confirm nothing regressed**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, all tests from Tasks 2–15.

- [ ] **Step 6: Commit**

```bash
git add windows/src/Slipstream.Core/Control windows/tests/Slipstream.Core.Tests/Control
git commit -m "feat: add fingerprint-pinned TLS control channel and real peer probe"
```

---

## Task 16: Control server, pairing handshake, and the verification harness

The task that turns a library into something you can run on two machines and watch work.

**Files:**
- Create: `windows/src/Slipstream.Core/Control/ControlServer.cs`
- Create: `windows/src/Slipstream.Core/SlipstreamPeer.cs`
- Create: `windows/tools/Slipstream.Harness/Slipstream.Harness.csproj`
- Create: `windows/tools/Slipstream.Harness/Program.cs`
- Create: `protocol/protocol.md`
- Test: `windows/tests/Slipstream.Core.Tests/Control/PairingHandshakeTests.cs`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `sealed record HelloPayload(int Version, string DeviceId, string Name, string Fingerprint)`
  - `sealed record PairOfferPayload(int Version, string DeviceId, string Name, string Fingerprint)`
  - `sealed class ControlServer : IAsyncDisposable { ControlServer(DeviceIdentity identity, PairedPeerStore peers, IPAddress bindAddress, int port); IPEndPoint ListenEndPoint { get; } event Func<ControlConnection, CancellationToken, Task>? PeerConnected; Task RunAsync(CancellationToken ct); }`
  - `sealed class SlipstreamPeer : IAsyncDisposable` — composes identity, store, server, strategies, coordinator; exposes `Task<DiscoveryResult?> FindPeerAsync(TimeSpan, CancellationToken)` and `Task StartAsync(CancellationToken)`.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Control/PairingHandshakeTests.cs`:

```csharp
using System.Net;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Control;

public class PairingHandshakeTests : IDisposable
{
    private readonly string _serverDir = Directory.CreateTempSubdirectory("slipstream-srv-").FullName;
    private readonly string _clientDir = Directory.CreateTempSubdirectory("slipstream-cli-").FullName;

    public void Dispose()
    {
        Directory.Delete(_serverDir, recursive: true);
        Directory.Delete(_clientDir, recursive: true);
    }

    [Fact]
    public async Task Paired_peers_exchange_hello_over_a_pinned_connection()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var clientIdentity = DeviceIdentity.CreateNew("Client Phone");

        var serverPeers = new PairedPeerStore(_serverDir);
        serverPeers.Pair(new PairedPeer(clientIdentity.DeviceId, clientIdentity.Fingerprint, "Client Phone", DateTimeOffset.UnixEpoch));

        var clientPeers = new PairedPeerStore(_clientDir);
        clientPeers.Pair(new PairedPeer(serverIdentity.DeviceId, serverIdentity.Fingerprint, "Server PC", DateTimeOffset.UnixEpoch));

        await using var server = new ControlServer(serverIdentity, serverPeers, IPAddress.Loopback, port: 0);

        server.PeerConnected += async (connection, token) =>
        {
            var hello = await connection.ReceiveAsync(token);
            Assert.Equal("hello", hello!.Type);

            await connection.SendAsync(ControlMessage.Response("hello.ok", hello.Id!, new HelloPayload(
                SlipstreamPorts.ProtocolVersion, serverIdentity.DeviceId,
                serverIdentity.DisplayName, serverIdentity.Fingerprint)), token);
        };

        var running = server.RunAsync(cts.Token);

        var client = new ControlClient(clientIdentity, clientPeers);
        await using var connection = await client.ConnectAsync(server.ListenEndPoint, TimeSpan.FromSeconds(5), cts.Token);

        Assert.NotNull(connection);
        Assert.Equal(serverIdentity.Fingerprint, connection.PeerFingerprint);

        await connection.SendAsync(ControlMessage.Request("hello", "1", new HelloPayload(
            SlipstreamPorts.ProtocolVersion, clientIdentity.DeviceId,
            clientIdentity.DisplayName, clientIdentity.Fingerprint)), cts.Token);

        var reply = await connection.ReceiveAsync(cts.Token);

        Assert.Equal("hello.ok", reply!.Type);
        Assert.Equal("1", reply.Id);
        Assert.Equal(serverIdentity.DeviceId, reply.PayloadAs<HelloPayload>()!.DeviceId);

        await cts.CancelAsync();
        await SwallowAsync(running);
    }

    [Fact]
    public async Task Server_drops_a_connection_from_an_untrusted_fingerprint()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var strangerIdentity = DeviceIdentity.CreateNew("Stranger");

        var serverPeers = new PairedPeerStore(_serverDir);
        serverPeers.Pair(new PairedPeer("someone-else", "a-different-fingerprint", "Other", DateTimeOffset.UnixEpoch));

        var strangerPeers = new PairedPeerStore(_clientDir);
        strangerPeers.Pair(new PairedPeer(serverIdentity.DeviceId, serverIdentity.Fingerprint, "Server PC", DateTimeOffset.UnixEpoch));

        var handled = false;

        await using var server = new ControlServer(serverIdentity, serverPeers, IPAddress.Loopback, port: 0);
        server.PeerConnected += (_, _) => { handled = true; return Task.CompletedTask; };

        var running = server.RunAsync(cts.Token);

        var client = new ControlClient(strangerIdentity, strangerPeers);

        // The TLS handshake may succeed; the server then drops it on the fingerprint check.
        try
        {
            await using var connection = await client.ConnectAsync(
                server.ListenEndPoint, TimeSpan.FromSeconds(3), cts.Token);

            if (connection is not null)
            {
                await connection.SendAsync(ControlMessage.Request("hello", "1"), cts.Token);
                Assert.Null(await connection.ReceiveAsync(cts.Token)); // stream closed
            }
        }
        catch (Exception) { /* an abrupt close is an acceptable outcome */ }

        Assert.False(handled);

        await cts.CancelAsync();
        await SwallowAsync(running);
    }

    private static async Task SwallowAsync(Task task)
    {
        try { await task; } catch (OperationCanceledException) { }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingHandshakeTests`
Expected: FAIL — `ControlServer` and `HelloPayload` do not exist.

- [ ] **Step 3: Write the server**

Create `windows/src/Slipstream.Core/Control/ControlServer.cs`:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Control;

public sealed record HelloPayload(int Version, string DeviceId, string Name, string Fingerprint);

public sealed record PairOfferPayload(int Version, string DeviceId, string Name, string Fingerprint);

/// <summary>
/// Inbound half of the control channel. Binds to a specific local address (spec §11
/// layer 1) and drops any connection whose client certificate is not the paired peer.
/// </summary>
public sealed class ControlServer : IAsyncDisposable
{
    private readonly DeviceIdentity _identity;
    private readonly PairedPeerStore _peers;
    private readonly TcpListener _listener;

    public ControlServer(DeviceIdentity identity, PairedPeerStore peers, IPAddress bindAddress, int port)
    {
        LanGuard.EnsureLocal(bindAddress);

        _identity = identity;
        _peers = peers;
        _listener = new TcpListener(bindAddress, port);
        _listener.Start();
    }

    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.LocalEndpoint;

    /// <summary>Raised once per accepted, fingerprint-verified connection.</summary>
    public event Func<ControlConnection, CancellationToken, Task>? PeerConnected;

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                continue;
            }

            _ = HandleAsync(client, cancellationToken);
        }
    }

    private async Task HandleAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;
            var remote = (IPEndPoint)client.Client.RemoteEndPoint!;

            // Spec §11 layer 2, applied to inbound connections too.
            if (!LanGuard.IsLocal(remote.Address))
            {
                client.Dispose();
                return;
            }

            var stream = await PinnedTls.AuthenticateAsServerAsync(
                client.GetStream(), _identity, cancellationToken);

            var fingerprint = PinnedTls.FingerprintOf(stream);

            if (!_peers.Trusts(fingerprint))
            {
                // Unpaired devices get nothing. No prompt, no override path.
                await stream.DisposeAsync();
                client.Dispose();
                return;
            }

            await using var connection = new ControlConnection(stream, fingerprint, remote);

            var handler = PeerConnected;
            if (handler is not null) await handler(connection, cancellationToken);
        }
        catch (Exception)
        {
            // A failed inbound connection is routine — never take the listener down.
        }
        finally
        {
            client.Dispose();
        }
    }

    public ValueTask DisposeAsync()
    {
        _listener.Stop();
        return ValueTask.CompletedTask;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingHandshakeTests`
Expected: PASS, 2 tests.

- [ ] **Step 5: Compose the peer facade**

Create `windows/src/Slipstream.Core/SlipstreamPeer.cs`:

```csharp
using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core;

/// <summary>
/// Wires the six modules together. One instance per running app.
/// </summary>
public sealed class SlipstreamPeer : IAsyncDisposable
{
    private readonly INetworkInfo _networkInfo = new NetworkInfo();
    private readonly MulticastStrategy _multicast;
    private readonly DiscoveryCoordinator _coordinator;
    private ControlServer? _server;

    public SlipstreamPeer(string stateDirectory, string displayName)
    {
        Identity = DeviceIdentity.LoadOrCreate(stateDirectory, displayName);
        Peers = new PairedPeerStore(stateDirectory);
        Client = new ControlClient(Identity, Peers);

        var cache = new EndpointCache(stateDirectory);
        var probe = Client.CreateProbe(TimeSpan.FromSeconds(3));

        _multicast = new MulticastStrategy(Identity, Peers, probe);

        _coordinator = new DiscoveryCoordinator(_networkInfo, cache,
        [
            new CachedEndpointStrategy(cache, probe),
            new GatewayProbeStrategy(probe),
            _multicast,
            new SubnetSweepStrategy(Client.CreateProbe(TimeSpan.FromMilliseconds(600))),
        ]);
    }

    public DeviceIdentity Identity { get; }

    public PairedPeerStore Peers { get; }

    public ControlClient Client { get; }

    public LocalNetwork? Network => _networkInfo.Current();

    /// <summary>Starts the listener and the multicast query responder.</summary>
    public Task StartAsync(CancellationToken cancellationToken)
    {
        var network = _networkInfo.Current()
            ?? throw new InvalidOperationException("No local network is available.");

        _server = new ControlServer(Identity, Peers, network.LocalAddress, SlipstreamPorts.Control);

        return Task.WhenAll(
            _server.RunAsync(cancellationToken),
            _multicast.RespondToQueriesAsync(cancellationToken));
    }

    public ControlServer Server =>
        _server ?? throw new InvalidOperationException("Call StartAsync first.");

    public Task<DiscoveryResult?> FindPeerAsync(TimeSpan timeout, CancellationToken cancellationToken) =>
        _coordinator.DiscoverAsync(timeout, cancellationToken);

    public async ValueTask DisposeAsync()
    {
        if (_server is not null) await _server.DisposeAsync();
        await _multicast.DisposeAsync();
    }
}
```

- [ ] **Step 6: Build the verification harness**

```bash
cd windows
dotnet new console -n Slipstream.Harness -o tools/Slipstream.Harness -f net9.0
dotnet sln add tools/Slipstream.Harness/Slipstream.Harness.csproj
dotnet add tools/Slipstream.Harness/Slipstream.Harness.csproj reference src/Slipstream.Core/Slipstream.Core.csproj
```

Replace `windows/tools/Slipstream.Harness/Program.cs`:

```csharp
using System.Diagnostics;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

var command = args.Length > 0 ? args[0] : "help";
var stateDir = Path.Combine(Path.GetTempPath(), "slipstream-harness", args.Length > 1 ? args[1] : "default");

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

switch (command)
{
    case "identity":
    {
        var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        Console.WriteLine($"Device id:   {peer.Identity.DeviceId}");
        Console.WriteLine($"Fingerprint: {peer.Identity.Fingerprint}");
        Console.WriteLine($"Network:     {peer.Network?.LocalAddress} gw={peer.Network?.Gateway} key={peer.Network?.Key}");
        break;
    }

    case "pair":
    {
        // Manual pairing: paste the other device's id, name, and fingerprint.
        if (args.Length < 5)
        {
            Console.WriteLine("Usage: pair <state> <peerDeviceId> <peerName> <peerFingerprint>");
            return 1;
        }

        var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        peer.Peers.Pair(new PairedPeer(args[2], args[4], args[3], DateTimeOffset.UtcNow));

        Console.WriteLine($"Paired with {args[3]}.");
        Console.WriteLine($"Confirm this code matches on both devices: {PairingCode.Derive(peer.Identity.Fingerprint, args[4])}");
        break;
    }

    case "serve":
    {
        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);

        peer.StartAsync(cts.Token).ContinueWith(_ => { }, TaskScheduler.Default).Forget();
        await Task.Delay(300, cts.Token);

        peer.Server.PeerConnected += async (connection, token) =>
        {
            Console.WriteLine($"Peer connected from {connection.RemoteEndPoint}");

            while (await connection.ReceiveAsync(token) is { } message)
            {
                Console.WriteLine($"  <- {message.Type} {message.Id}");

                if (message.Type == "hello")
                {
                    await connection.SendAsync(ControlMessage.Response("hello.ok", message.Id!, new HelloPayload(
                        SlipstreamPorts.ProtocolVersion, peer.Identity.DeviceId,
                        peer.Identity.DisplayName, peer.Identity.Fingerprint)), token);
                }
                else if (message.Type == "ping")
                {
                    await connection.SendAsync(ControlMessage.Response("pong", message.Id!), token);
                }
            }
        };

        Console.WriteLine($"Listening on {peer.Server.ListenEndPoint}. Ctrl+C to stop.");
        await Task.Delay(Timeout.Infinite, cts.Token);
        break;
    }

    case "find":
    {
        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);

        var stopwatch = Stopwatch.StartNew();
        var result = await peer.FindPeerAsync(TimeSpan.FromSeconds(10), cts.Token);
        stopwatch.Stop();

        if (result is null)
        {
            Console.WriteLine($"Peer not found after {stopwatch.ElapsedMilliseconds} ms.");
            return 1;
        }

        Console.WriteLine($"Found {result.Peer.Peer.DisplayName} at {result.Peer.Endpoint}");
        Console.WriteLine($"  strategy: {result.StrategyName}");
        Console.WriteLine($"  elapsed:  {stopwatch.ElapsedMilliseconds} ms");

        await using var connection = await peer.Client.ConnectAsync(
            result.Peer.Endpoint, TimeSpan.FromSeconds(5), cts.Token);

        if (connection is not null)
        {
            await connection.SendAsync(ControlMessage.Request("hello", "1", new HelloPayload(
                SlipstreamPorts.ProtocolVersion, peer.Identity.DeviceId,
                peer.Identity.DisplayName, peer.Identity.Fingerprint)), cts.Token);

            var reply = await connection.ReceiveAsync(cts.Token);
            Console.WriteLine($"  handshake: {reply?.Type}");
        }
        break;
    }

    default:
        Console.WriteLine("Commands: identity <state> | pair <state> <id> <name> <fingerprint> | serve <state> | find <state>");
        break;
}

return 0;

internal static class TaskExtensions
{
    public static void Forget(this Task task) => _ = task;
}
```

- [ ] **Step 7: Verify on one machine (loopback)**

```bash
cd windows
dotnet run --project tools/Slipstream.Harness -- identity alice
dotnet run --project tools/Slipstream.Harness -- identity bob
```

Pair them using each other's printed fingerprints, then in two terminals:

```bash
dotnet run --project tools/Slipstream.Harness -- serve alice
```

```bash
dotnet run --project tools/Slipstream.Harness -- find bob
```

Expected: `find` reports a strategy name, an elapsed time, and `handshake: hello.ok`. Confirm the pairing codes printed by both `pair` invocations are identical — that is §4's order-independence verified end to end.

- [ ] **Step 8: Verify across two real machines**

Run `serve` on one PC and `find` on another, then repeat across all three network conditions from the spec's discovery matrix:

| Scenario | Expected winning strategy |
|---|---|
| Both on external WiFi | `multicast`, or `subnet-sweep` if the AP drops it |
| PC B on PC A's hotspot | `gateway-probe` |
| Repeat run on the same network | `cached-endpoint` |

Record the actual strategy and elapsed time for each. If `gateway-probe` does not win the hotspot case, that is a bug in Task 10, not a tuning issue.

- [ ] **Step 9: Write the protocol document**

Create `protocol/protocol.md` capturing what is now implemented, so Plan 3's Kotlin implementation has a single authority. Include: the port table, the announcement JSON schema and its two vector cases, the pairing-code derivation with its vector cases, the TLS pinning rule, the JSON-lines framing rule with the 1 MB line cap and skip-malformed behaviour, and the `hello` / `hello.ok` / `ping` / `pong` message shapes with example lines. Mark bulk transfer, media, listing, play, and clipboard as "specified in the design document, implemented in Plan 2".

- [ ] **Step 10: Run the whole suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, all tests.

- [ ] **Step 11: Commit**

```bash
git add windows protocol/protocol.md
git commit -m "feat: add control server, peer facade, and verification harness"
```

---

## Self-Review

**Spec coverage.** Every §3–§6 and §11 requirement in scope for this plan maps to a task:

| Spec requirement | Task |
|---|---|
| §3 ports and module split | 2, and the file structure above |
| §4 device id, self-signed cert, fingerprint | 3 |
| §4 order-independent 6-digit pairing code | 4 |
| §4 exactly one paired peer, re-pairing replaces | 5 |
| §4 fingerprint-pin only, CA validation disabled | 15 |
| §4 unpaired devices get nothing | 16 |
| §5 S1 cached endpoint, keyed per network | 7, 9 |
| §5 S2 gateway probe | 10 |
| §5 S3 multicast, `224.0.0.167`, unicast reply | 11 |
| §5 S4 sweep, bounded to /24, concurrent | 8, 12 |
| §5 race four strategies, first wins, cancel rest | 13 |
| §6 JSON-lines framing, unknown types ignored | 14 |
| §6 `hello` / `ping` message families | 16 |
| §11 layer 1 bind to local interface | 16 |
| §11 layer 2 RFC1918 / link-local refusal, both directions | 2, 15, 16 |
| §11 layer 4 no third-party runtime dependencies | 1 |

**Deliberately out of scope, deferred to Plan 2:** §5 network-change re-discovery (needs a live connection to tear down, which arrives with the transfer engine), §6 `list` / `stat` / `pull` / `push` / `play` / `clipboard` handlers, §7 transfer engine, §8 media server, §9 thumbnails. §11 layer 3 (Android `Network` binding) belongs to Plan 3.

**Placeholder scan.** No `TBD`, `TODO`, "similar to Task N", or "add error handling" instructions. Every code step carries complete code. The one `PENDING` value in `protocol/vectors/pairing-codes.json` is filled in by Task 4 Step 5, and Task 4's own test asserts it is no longer `PENDING`.

**Type consistency.** Checked across tasks: `DiscoveredPeer(PairedPeer, IPEndPoint)` is constructed identically in Tasks 9–13, 15. `PeerProbe` keeps the signature `(IPEndPoint, CancellationToken) -> Task<DiscoveredPeer?>` from Task 7 through Task 15. `IDiscoveryStrategy.FindAsync(LocalNetwork, CancellationToken)` matches in all four strategies and the coordinator. `LocalNetwork(LocalAddress, Gateway, PrefixLength, Key)` is constructed with the same four positional arguments in every test. `Fingerprint.Of` has both an `X509Certificate2` and a `ReadOnlySpan<byte>` overload, and Task 15 uses the span overload with `GetRawCertData()` — consistent with Task 3's DER definition. `HelloPayload` is declared once, in Task 16's `ControlServer.cs`, and Task 14's test uses a distinct private record of the same shape to avoid a forward dependency.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-25-core-discovery-control.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, with review between tasks and fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batching with checkpoints for review.

Which approach?
