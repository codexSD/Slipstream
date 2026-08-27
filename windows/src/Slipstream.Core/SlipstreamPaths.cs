namespace Slipstream.Core;

/// <summary>
/// Where Slipstream keeps everything that must survive a restart: this device's identity and
/// certificate, the paired peer, settings, history, the endpoint cache, and the log.
///
/// This exists because <see cref="Environment.SpecialFolder.LocalApplicationData"/> is not a
/// stable location for this app. A packaged WinUI process has its LOCALAPPDATA redirected into
/// its own MSIX container, and a process launched from inside another packaged app inherits
/// *that* app's container instead. The same executable therefore resolved to different
/// directories depending on how it was started, and each one grew its own identity.pfx — so
/// the PC presented a different TLS certificate after a restart, and the phone, which pins the
/// fingerprint it paired with, correctly refused to recognise it. The pairing looked like it
/// had gone stale; in fact the identity had moved.
///
/// The user profile is the one root that means the same thing in every launch context.
/// </summary>
public static class SlipstreamPaths
{
    /// <summary>Dot-prefixed so it does not clutter the profile the user actually browses.</summary>
    public static string StateDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".slipstream");

    public static string LogFile => Path.Combine(StateDirectory, "slipstream.log");

    /// <summary>
    /// Moves state written under the old, launch-context-dependent location into
    /// <see cref="StateDirectory"/>, so an existing pairing and settings survive this change
    /// rather than silently resetting.
    ///
    /// Only ever copies into an empty destination, and never overwrites: if several of the old
    /// containers hold an identity, the first one found wins and the rest are left alone. There
    /// is no way to tell which of them the peer actually paired with, and inventing an answer
    /// would be worse than the one re-pair this migration cannot always avoid.
    /// </summary>
    public static void MigrateLegacyState()
    {
        try
        {
            Directory.CreateDirectory(StateDirectory);

            // Already carrying an identity: this has run before, or the user paired since.
            if (File.Exists(Path.Combine(StateDirectory, "identity.pfx"))) return;

            var legacy = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Slipstream");

            if (!Directory.Exists(legacy)) return;

            foreach (var file in Directory.EnumerateFiles(legacy))
            {
                var destination = Path.Combine(StateDirectory, Path.GetFileName(file));
                if (!File.Exists(destination)) File.Copy(file, destination);
            }
        }
        catch (Exception)
        {
            // A failed migration costs one re-pair. Failing to start costs everything.
        }
    }
}
