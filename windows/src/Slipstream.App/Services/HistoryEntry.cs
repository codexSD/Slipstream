namespace Slipstream.App.Services;

/// <summary>
/// One completed transfer, persisted to disk by <see cref="HistoryStore"/>. Deliberately a
/// plain immutable record (not an ObservableObject like <see cref="TransferItem"/>): history
/// rows never change in place once written, so there is nothing to bind PropertyChanged to —
/// only whole-collection add/evict.
/// </summary>
public sealed record HistoryEntry(
    string RemotePath,
    string LocalPath,
    long TotalBytes,
    TransferStatus Status,
    DateTimeOffset CompletedAtUtc)
{
    /// <summary>File name only, for display.</summary>
    public string Name => System.IO.Path.GetFileName(RemotePath);
}
