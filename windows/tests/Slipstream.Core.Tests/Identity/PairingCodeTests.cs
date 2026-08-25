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
