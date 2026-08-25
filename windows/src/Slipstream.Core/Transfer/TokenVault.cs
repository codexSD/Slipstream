using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace Slipstream.Core.Transfer;

/// <summary>
/// Spec §7 and §8. Tokens are issued only over the TLS control channel and are what
/// authenticate the plaintext bulk and media paths. In-memory by design: an app
/// restart invalidates everything, which is half of the media-token expiry rule.
/// </summary>
public sealed class TokenVault(TimeProvider? time = null)
{
    private static readonly TimeSpan MediaLifetime = TimeSpan.FromHours(12);

    // Spec §7. Scoped to one transfer id and one file path, minted over TLS to an
    // already-paired peer. The former per-stream use counter was removed: the client
    // cannot know its range count until after it holds the token, so the server could
    // not size the budget, and a fragmented resume legitimately needs more connections
    // than there are streams. A tighter expiry replaces it — a stricter bound than the
    // counter ever was, and one that does not break a correct client.
    private static readonly TimeSpan BulkLifetime = TimeSpan.FromMinutes(5);

    private readonly TimeProvider _time = time ?? TimeProvider.System;
    private readonly ConcurrentDictionary<Guid, Entry> _entries = new();

    private sealed class Entry(TransferToken token, int remainingUses)
    {
        public TransferToken Token { get; } = token;
        public int RemainingUses = remainingUses;
    }

    public TransferToken IssueBulk(Guid transferId, string path, long size, int expectedStreams)
    {
        var token = new TransferToken(
            NewToken(), transferId, path, size, _time.GetUtcNow() + BulkLifetime);

        _ = expectedStreams; // retained for call-site compatibility; no longer a budget
        _entries[token.Value] = new Entry(token, int.MaxValue);
        return token;
    }

    /// <summary>Consumes one use. Returns null when unknown, expired, exhausted, or mis-scoped.</summary>
    public TransferToken? ValidateBulk(Guid token, Guid transferId)
    {
        if (!_entries.TryGetValue(token, out var entry)) return null;
        if (entry.Token.TransferId != transferId) return null;
        if (_time.GetUtcNow() > entry.Token.ExpiresAt) return null;

        // One use per expected stream — a 4-stream transfer legitimately presents
        // the same token four times.
        if (Interlocked.Decrement(ref entry.RemainingUses) < 0)
        {
            Interlocked.Increment(ref entry.RemainingUses);
            return null;
        }

        return entry.Token;
    }

    public TransferToken IssueMedia(string path, long size)
    {
        var token = new TransferToken(
            NewToken(), Guid.Empty, path, size, _time.GetUtcNow() + MediaLifetime);

        // Media is seeked repeatedly; uses are effectively unlimited within the lifetime.
        _entries[token.Value] = new Entry(token, int.MaxValue);
        return token;
    }

    public TransferToken? ValidateMedia(Guid token)
    {
        if (!_entries.TryGetValue(token, out var entry)) return null;
        if (_time.GetUtcNow() > entry.Token.ExpiresAt)
        {
            _entries.TryRemove(token, out _);
            return null;
        }

        return entry.Token;
    }

    public void Revoke(Guid transferId)
    {
        foreach (var pair in _entries)
            if (pair.Value.Token.TransferId == transferId)
                _entries.TryRemove(pair.Key, out _);
    }

    private static Guid NewToken() => new(RandomNumberGenerator.GetBytes(16));
}
