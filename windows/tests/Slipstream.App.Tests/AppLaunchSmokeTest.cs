using System.Diagnostics;

namespace Slipstream.App.Tests;

/// <summary>
/// The one test in this suite that starts the actual executable.
///
/// Every other test here is a view-model or source-shape test, and the whole suite passed
/// green through an entire plan while Slipstream.App.exe died during Window.Activate() and
/// never showed a window. Nothing was ever launching the process, so nothing could notice.
/// This test closes that hole: it runs the built app, waits for a real top-level window to
/// appear, and kills it again.
///
/// It deliberately has no environment switch. A smoke test that opts itself out on the
/// machine where it matters is the bug it exists to prevent.
/// </summary>
public class AppLaunchSmokeTest
{
    /// <summary>Generous on purpose: a cold self-contained WinUI start on a slow or loaded
    /// machine (CI, first run after a build) can take many seconds before the window is up.
    /// The failure mode this test guards against is "no window, ever", not "slow window".</summary>
    private static readonly TimeSpan WindowTimeout = TimeSpan.FromSeconds(60);

    [Fact]
    public void The_built_executable_starts_and_shows_a_window()
    {
        var exe = LocateExecutable();

        // The app enforces single-instance via a Global\ mutex: a second launch signals the
        // first window and exits(0) immediately. If one is already running, this test would
        // "pass" against a process it never started, so refuse to run against that state.
        var alreadyRunning = Process.GetProcessesByName("Slipstream.App");
        Assert.True(alreadyRunning.Length == 0,
            "A Slipstream.App process is already running. Its single-instance mutex makes any new " +
            "launch exit immediately, so this smoke test cannot tell whether the built executable " +
            "actually works. Close the running instance and re-run.");

        var crashLogBefore = ReadCrashLog();

        using var process = Process.Start(new ProcessStartInfo(exe)
        {
            WorkingDirectory = Path.GetDirectoryName(exe)!,
            UseShellExecute = true,
        })!;

        try
        {
            var deadline = DateTime.UtcNow + WindowTimeout;
            string? title = null;

            while (DateTime.UtcNow < deadline)
            {
                process.Refresh();

                if (process.HasExited)
                    break;

                // A non-empty MainWindowTitle is the signal that a real top-level window
                // exists. It stays populated even if the window opens minimized, which is
                // what happens when the app is started without stealing focus.
                if (process.MainWindowHandle != IntPtr.Zero &&
                    !string.IsNullOrEmpty(process.MainWindowTitle))
                {
                    title = process.MainWindowTitle;
                    break;
                }

                Thread.Sleep(250);
            }

            var crash = CrashLogAddition(crashLogBefore);

            Assert.True(title is not null,
                $"Slipstream.App.exe never showed a window within {WindowTimeout.TotalSeconds:0}s." +
                (process.HasExited ? $" The process exited with code {process.ExitCode}." : " The process was still running.") +
                (crash is null ? string.Empty : $"{Environment.NewLine}Startup crash log said:{Environment.NewLine}{crash}") +
                $"{Environment.NewLine}Executable: {exe}");

            Assert.Equal("Slipstream", title);
        }
        finally
        {
            try
            {
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                    process.WaitForExit(10_000);
                }
            }
            catch (InvalidOperationException)
            {
                // Raced with the process exiting on its own; nothing left to clean up.
            }
        }
    }

    /// <summary>
    /// Resolves the app's own build output, not the copy sitting next to the test assembly:
    /// only the app's output folder carries the self-contained Windows App SDK payload the
    /// executable needs to start at all.
    /// </summary>
    private static string LocateExecutable()
    {
        // .../windows/tests/Slipstream.App.Tests/bin/<Platform>/<Configuration>/<tfm>/
        var testOutput = new DirectoryInfo(AppContext.BaseDirectory);
        var configuration = testOutput.Parent!.Name;
        var platform = testOutput.Parent!.Parent!.Name;
        var targetFramework = testOutput.Name;

        var appOutput = Path.Combine(
            Path.GetDirectoryName(TestPaths.App("."))!,
            "bin", platform, configuration, targetFramework, $"win-{platform.ToLowerInvariant()}",
            "Slipstream.App.exe");

        Assert.True(File.Exists(appOutput),
            $"Expected the built app at {appOutput}. Build Slipstream.App for Platform={platform} " +
            $"Configuration={configuration} before running this test.");

        return appOutput;
    }

    private static string ReadCrashLog()
    {
        var path = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Slipstream", "startup-error.log");

        return File.Exists(path) ? File.ReadAllText(path) : string.Empty;
    }

    /// <summary>Whatever the app appended to its startup crash log during this launch — the
    /// difference is what makes an otherwise opaque "no window appeared" failure diagnosable.</summary>
    private static string? CrashLogAddition(string before)
    {
        var after = ReadCrashLog();
        return after.Length > before.Length ? after[before.Length..].Trim() : null;
    }
}
