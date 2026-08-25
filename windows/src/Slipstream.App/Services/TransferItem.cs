using CommunityToolkit.Mvvm.ComponentModel;
using Slipstream.Core.Transfer;

namespace Slipstream.App.Services;

/// <summary>
/// One row of the Transfers page: a single file moving through a <see cref="TransferQueue"/>.
/// Progress arrives from Core as raw byte counters (<see cref="TransferProgress"/>); this class
/// owns turning those into the tabular, pre-formatted strings MeridianDataGrid displays
/// (size/rate/ETA), since the grid resolves cell text via reflection on plain properties rather
/// than converters (see MeridianDataGrid's class remarks).
/// </summary>
public sealed partial class TransferItem : ObservableObject
{
    private const double Kb = 1024d;
    private const double Mb = Kb * 1024d;
    private const double Gb = Mb * 1024d;

    private long _totalBytes;
    private long _bytesCompleted;
    private double _bytesPerSecond;
    private TransferStatus _status = TransferStatus.Queued;
    private string _statusText = "Queued";
    private string _sizeText;
    private string _rateText = "—";
    private string _etaText = "—";

    public TransferItem(string path, long totalBytes = 0)
    {
        Path = path;
        Name = System.IO.Path.GetFileName(path);
        _totalBytes = totalBytes;
        _sizeText = FormatSize(0, totalBytes);
    }

    /// <summary>The remote path this transfer pulls (what was passed to IPeerHost.PullAsync).</summary>
    public string Path { get; }

    /// <summary>File name only, for display.</summary>
    public string Name { get; }

    public long TotalBytes
    {
        get => _totalBytes;
        private set => SetProperty(ref _totalBytes, value);
    }

    public long BytesCompleted
    {
        get => _bytesCompleted;
        private set => SetProperty(ref _bytesCompleted, value);
    }

    public double BytesPerSecond
    {
        get => _bytesPerSecond;
        private set => SetProperty(ref _bytesPerSecond, value);
    }

    public TransferStatus Status
    {
        get => _status;
        set
        {
            if (!SetProperty(ref _status, value)) return;
            StatusText = value switch
            {
                TransferStatus.Queued => "Queued",
                TransferStatus.Running => "Running",
                TransferStatus.Complete => "Complete",
                TransferStatus.Failed => "Failed",
                _ => value.ToString(),
            };
        }
    }

    /// <summary>Sentence-case status text for the grid's Status column.</summary>
    public string StatusText
    {
        get => _statusText;
        private set => SetProperty(ref _statusText, value);
    }

    /// <summary>Tabular "completed / total" text, e.g. "1.0 / 4.0 GB".</summary>
    public string SizeText
    {
        get => _sizeText;
        private set => SetProperty(ref _sizeText, value);
    }

    /// <summary>Tabular throughput text, e.g. "50.0 MB/s".</summary>
    public string RateText
    {
        get => _rateText;
        private set => SetProperty(ref _rateText, value);
    }

    /// <summary>Tabular time-remaining text, e.g. "1m 1s left".</summary>
    public string EtaText
    {
        get => _etaText;
        private set => SetProperty(ref _etaText, value);
    }

    /// <summary>Applies one throttled progress report from Core, recomputing every display string.</summary>
    public void Apply(TransferProgress progress)
    {
        BytesCompleted = progress.BytesCompleted;
        if (progress.TotalBytes > 0)
            TotalBytes = progress.TotalBytes;
        BytesPerSecond = progress.BytesPerSecond;

        SizeText = FormatSize(BytesCompleted, TotalBytes);
        RateText = FormatRate(BytesPerSecond);
        EtaText = FormatEta(TotalBytes - BytesCompleted, BytesPerSecond);

        if (Status == TransferStatus.Queued)
            Status = TransferStatus.Running;
    }

    private static string FormatSize(long completed, long total)
    {
        var (divisor, unit) = SplitUnit(Math.Max(completed, total));
        return $"{completed / divisor:0.0} / {total / divisor:0.0} {unit}";
    }

    /// <summary>Public so DeviceViewModel's hero-metric can reuse the same rate formatting.</summary>
    public static string FormatRate(double bytesPerSecond)
    {
        var (divisor, unit) = SplitUnit(bytesPerSecond);
        return $"{bytesPerSecond / divisor:0.0} {unit}/s";
    }

    private static string FormatEta(long remainingBytes, double bytesPerSecond)
    {
        if (bytesPerSecond <= 0 || remainingBytes <= 0)
            return "—";

        var totalSeconds = (int)Math.Round(remainingBytes / bytesPerSecond);
        var minutes = totalSeconds / 60;
        var seconds = totalSeconds % 60;
        return $"{minutes}m {seconds}s left";
    }

    private static (double Divisor, string Unit) SplitUnit(double bytes) => bytes switch
    {
        >= Gb => (Gb, "GB"),
        >= Mb => (Mb, "MB"),
        >= Kb => (Kb, "KB"),
        _ => (1d, "B"),
    };
}
