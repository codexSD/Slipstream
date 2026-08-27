using System.Text;

namespace Slipstream.Core.Diagnostics;

/// <summary>
/// One place every part of Slipstream reports what it is actually doing.
///
/// There was no logging anywhere in either app, so every failure looked identical from the
/// outside — "Couldn't load this folder." was, at various times, a missing path, a peer that
/// had never connected, and a serialization mismatch, with nothing to tell them apart.
/// Diagnosing on real hardware meant guessing. This exists so it does not have to.
///
/// Deliberately always-on and file-backed: the desktop app has no console, so Debug.WriteLine
/// reaches nobody once it is published. Spec §11 is unaffected — this writes to one local file
/// and nowhere else.
/// </summary>
public static class SlipstreamLog
{
    private static readonly Lock Gate = new();

    /// <summary>Rolled at this size so an always-on log cannot grow without bound.</summary>
    private const long MaxBytes = 4 * 1024 * 1024;

    public static string Path { get; } = SlipstreamPaths.LogFile;

    public static void Info(string area, string message) => Write(area, message, null);

    public static void Warn(string area, string message, Exception? error = null) =>
        Write(area, "WARN " + message, error);

    private static void Write(string area, string message, Exception? error)
    {
        var line = new StringBuilder()
            .Append(DateTime.Now.ToString("HH:mm:ss.fff"))
            .Append(" [").Append(area).Append("] ").Append(message);

        if (error is not null) line.Append(" :: ").Append(error.GetType().Name).Append(": ").Append(error.Message);

        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);

                // Roll rather than truncate: the run that just failed is the interesting one,
                // and truncating on startup would throw it away the moment the user retried.
                if (File.Exists(Path) && new FileInfo(Path).Length > MaxBytes)
                    File.Move(Path, Path + ".1", overwrite: true);

                File.AppendAllText(Path, line.AppendLine().ToString());
            }
        }
        catch (Exception)
        {
            // Logging must never be the thing that breaks a transfer.
        }
    }
}
