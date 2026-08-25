namespace Slipstream.App.Services;

/// <summary>Lifecycle of one <see cref="TransferItem"/> as it moves through a <see cref="TransferQueue"/>.</summary>
public enum TransferStatus
{
    Queued,
    Running,
    Complete,
    Failed,
}
