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
