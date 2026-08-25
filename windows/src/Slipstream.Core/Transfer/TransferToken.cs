namespace Slipstream.Core.Transfer;

public sealed record TransferToken(
    Guid Value,
    Guid TransferId,
    string Path,
    long Size,
    DateTimeOffset ExpiresAt);
