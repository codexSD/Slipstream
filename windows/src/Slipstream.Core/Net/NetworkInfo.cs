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

/// <summary>
/// One live network interface reduced to plain data: everything the ranking needs, and
/// nothing that requires a real <see cref="NetworkInterface"/> to fake in a unit test.
/// </summary>
internal sealed record InterfaceCandidate(
    string Id,
    string Name,
    string Description,
    NetworkInterfaceType Type,
    int Index,
    IPAddress Address,
    int PrefixLength,
    IPAddress? Gateway);

public sealed class NetworkInfo : INetworkInfo
{
    private readonly Func<IEnumerable<InterfaceCandidate>> _candidates;

    public NetworkInfo() : this(LiveCandidates) { }

    internal NetworkInfo(Func<IEnumerable<InterfaceCandidate>> candidates) => _candidates = candidates;

    public LocalNetwork? Current() => Select(_candidates());

    /// <summary>
    /// Ranks candidates rather than taking the first one enumeration happens to hand back.
    /// Taking the first was a real defect: on a developer machine with Hyper-V installed it
    /// reliably picked <c>vEthernet (Default Switch)</c> — a virtual switch with no default
    /// gateway and a /20 prefix — which starves the whole discovery ladder at once. S2
    /// (gateway probe) cannot run without a gateway, and S4 (subnet sweep) correctly refuses
    /// anything wider than a /24, so nothing is left to find the peer with.
    /// </summary>
    /// <remarks>
    /// The keys, in order, lowest wins. Each only orders candidates already equal on every
    /// key above it.
    /// <list type="number">
    /// <item><b>Has a usable default gateway.</b> The decisive signal: an interface you can
    /// actually route on. See <see cref="UsableGateway"/> for what "usable" excludes.</item>
    /// <item><b>Physical beats virtual.</b> Hyper-V switches, VPN/TAP tunnels, Bluetooth PANs
    /// and the like carry no path to a phone. See <see cref="IsVirtual"/>.</item>
    /// <item><b>Non-APIPA beats APIPA.</b> A 169.254.* address means no DHCP happened here —
    /// the adapter is up but nothing is on the other end.</item>
    /// <item><b>Lowest interface index, then ordinal interface id.</b> A stable, documented,
    /// reproducible tie-break — as opposed to enumeration order, which is exactly the bug.</item>
    /// </list>
    /// Note the Windows-as-hotspot-host case, the mirror image of the Android AP-binding fix
    /// (see <c>docs/superpowers/plans/2026-08-25-android-core-deviations.md</c>): when this PC
    /// hosts the hotspot, its own AP interface has no default gateway, so key 1 demotes it.
    /// That does not make it unreachable, because it is the *only* thing left when no interface
    /// has a gateway, and keys 2 and 3 then put it ahead of every virtual switch and every
    /// APIPA adapter — provided the hosted-network adapter is not itself misread as virtual,
    /// which is why <see cref="IsVirtual"/> carves it out by name explicitly.
    /// </remarks>
    internal static LocalNetwork? Select(IEnumerable<InterfaceCandidate> candidates)
    {
        var chosen = candidates
            .OrderBy(c => UsableGateway(c) is null ? 1 : 0)
            .ThenBy(c => IsVirtual(c) ? 1 : 0)
            .ThenBy(c => IsAutoConfigured(c.Address) ? 1 : 0)
            .ThenBy(c => c.Index)
            .ThenBy(c => c.Id, StringComparer.Ordinal)
            .FirstOrDefault();

        return chosen is null ? null : ToLocalNetwork(chosen);
    }

    private static LocalNetwork ToLocalNetwork(InterfaceCandidate candidate)
    {
        var prefix = NormalizePrefix(candidate.PrefixLength);

        // Keyed on the interface plus the subnet, so moving between networks on the
        // same adapter produces a different key.
        var key = $"{candidate.Id}|{MaskToNetwork(candidate.Address, prefix)}/{prefix}";

        return new LocalNetwork(candidate.Address, UsableGateway(candidate), prefix, key);
    }

    private static int NormalizePrefix(int prefixLength) =>
        prefixLength is > 0 and <= 32 ? prefixLength : 24;

    /// <summary>
    /// The candidate's gateway, or null when it cannot be routed through. Windows reports
    /// gateway entries that are not usable next hops, and treating one as real would make the
    /// ranking's decisive key meaningless: it must be a LAN-local IPv4 address that sits inside
    /// this interface's own subnet and is a genuine host address there — not the subnet's
    /// network or broadcast address, and not this interface's own address. (The live machine
    /// that exposed this reported <c>192.168.43.0</c> — a /24's network address — as the
    /// gateway of one adapter.)
    /// </summary>
    private static IPAddress? UsableGateway(InterfaceCandidate candidate)
    {
        var gateway = candidate.Gateway;
        if (gateway is null) return null;
        if (gateway.AddressFamily != AddressFamily.InterNetwork) return null;
        if (gateway.Equals(IPAddress.Any) || IPAddress.IsLoopback(gateway)) return null;
        if (!LanGuard.IsLocal(gateway)) return null;
        if (gateway.Equals(candidate.Address)) return null;

        var prefix = NormalizePrefix(candidate.PrefixLength);
        if (!MaskToNetwork(gateway, prefix).Equals(MaskToNetwork(candidate.Address, prefix))) return null;

        // Network and broadcast addresses of the subnet are never a next hop. (A /31 or /32
        // point-to-point link has neither, so the check does not apply there.)
        if (prefix <= 30)
        {
            var value = ToUInt32(gateway);
            var mask = uint.MaxValue << (32 - prefix);
            var network = value & mask;
            if (value == network || value == (network | ~mask)) return null;
        }

        return gateway;
    }

    /// <summary>APIPA — 169.254.0.0/16. "No DHCP server ever answered on this adapter."</summary>
    private static bool IsAutoConfigured(IPAddress address)
    {
        Span<byte> bytes = stackalloc byte[4];
        return address.AddressFamily == AddressFamily.InterNetwork &&
               address.TryWriteBytes(bytes, out _) &&
               bytes[0] == 169 && bytes[1] == 254;
    }

    /// <summary>
    /// Adapters that exist in software and carry no path to a device on the physical LAN:
    /// Hyper-V/WSL switches, VM host-only adapters, VPN and TAP/TUN tunnels, Bluetooth PANs.
    /// </summary>
    /// <remarks>
    /// Windows names its hosted-network (Mobile Hotspot) adapter "Local Area Connection* N"
    /// with a description of "Microsoft Wi-Fi Direct Virtual Adapter". That word "Virtual"
    /// would sweep the PC's own AP interface into this bucket, and since a hosted AP also has
    /// no default gateway it would then lose both of the top two ranking keys and become
    /// effectively unselectable — reintroducing, on the Windows side, exactly the bug the
    /// Android AP-binding fix closed. It is backed by a real radio, so it is carved out here.
    /// </remarks>
    private static bool IsVirtual(InterfaceCandidate candidate)
    {
        var name = candidate.Name.ToLowerInvariant();
        var description = candidate.Description.ToLowerInvariant();

        if (name.StartsWith("local area connection*", StringComparison.Ordinal) ||
            description.Contains("wi-fi direct", StringComparison.Ordinal) ||
            description.Contains("wifi direct", StringComparison.Ordinal))
        {
            return false;
        }

        if (candidate.Type is NetworkInterfaceType.Tunnel or NetworkInterfaceType.Ppp) return true;

        return VirtualMarkers.Any(marker =>
            name.Contains(marker, StringComparison.Ordinal) ||
            description.Contains(marker, StringComparison.Ordinal));
    }

    private static readonly string[] VirtualMarkers =
    [
        "vethernet", "hyper-v", "virtual", "vmware", "virtualbox", "vbox", "bluetooth",
        "vpn", "tap-windows", "tap-win", "tunnel", "teredo", "pseudo", "wintun", "wireguard",
        "tailscale", "zerotier", "docker", "wsl", "loopback",
    ];

    private static uint ToUInt32(IPAddress address)
    {
        Span<byte> bytes = stackalloc byte[4];
        address.TryWriteBytes(bytes, out _);
        return BinaryPrimitives.ReadUInt32BigEndian(bytes);
    }

    /// <summary>
    /// Snapshot of this machine's live interfaces: every up, non-loopback adapter that has a
    /// <see cref="LanGuard"/>-local IPv4 address.
    /// </summary>
    private static IEnumerable<InterfaceCandidate> LiveCandidates()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            IPInterfaceProperties properties;
            try { properties = nic.GetIPProperties(); }
            catch (NetworkInformationException) { continue; }

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

            var index = 0;
            try { index = properties.GetIPv4Properties()?.Index ?? 0; }
            catch (NetworkInformationException) { /* adapter has no IPv4 properties */ }

            yield return new InterfaceCandidate(
                nic.Id, nic.Name, nic.Description, nic.NetworkInterfaceType, index,
                unicast.Address, unicast.PrefixLength, gateway);
        }
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
