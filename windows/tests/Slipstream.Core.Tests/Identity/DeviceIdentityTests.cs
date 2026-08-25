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
