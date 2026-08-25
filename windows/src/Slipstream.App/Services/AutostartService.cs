using System.Diagnostics;

namespace Slipstream.App.Services;

/// <summary>
/// Registers/unregisters a per-user Task Scheduler logon task that launches this app's own
/// executable at sign-in, per plan §14: "Task Scheduler entry at logon, running to the system
/// tray... not a Run key — it survives better and can run without a console flash."
/// </summary>
/// <remarks>
/// <para>
/// Implemented via <c>schtasks.exe</c> rather than the <c>Microsoft.Win32.TaskScheduler</c>
/// NuGet package or the raw COM Task Scheduler API: it needs no new dependency, it is the same
/// surface Windows itself exposes to scripts/installers for exactly this kind of per-user
/// logon task, and creating a task scoped to the current user (<c>/RL LIMITED</c>, no
/// <c>/RU</c>/<c>/RP</c>) does not require elevation — a plain user process can create,
/// query, and delete its own logon task.
/// </para>
/// <para>
/// <see cref="Enable"/> is idempotent: <c>schtasks /create /F</c> overwrites an existing task
/// of the same name instead of failing, so calling it twice leaves one well-formed task, not
/// an error or a duplicate.
/// </para>
/// </remarks>
public sealed class AutostartService
{
    private readonly string _taskName;
    private readonly string _executablePath;

    public AutostartService(string taskName)
        : this(taskName, Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName
            ?? throw new InvalidOperationException("Could not determine the running executable's path."))
    {
    }

    /// <summary>Test/DI seam for the executable path the created task should launch.</summary>
    public AutostartService(string taskName, string executablePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(taskName);
        ArgumentException.ThrowIfNullOrWhiteSpace(executablePath);

        _taskName = taskName;
        _executablePath = executablePath;
    }

    /// <summary>Whether a Task Scheduler task named <c>taskName</c> currently exists for the
    /// current user. Queried live via <c>schtasks /query</c> rather than cached — this is the
    /// real OS state, not a preference this class remembers on its own.</summary>
    public bool IsEnabled => Query() == 0;

    /// <summary>Creates (or, if already present, overwrites in place) the logon task that
    /// launches this app's executable at sign-in. Idempotent: calling this twice never errors
    /// and never leaves more than one task registered.</summary>
    public void Enable()
    {
        var args = $"/Create /F /SC ONLOGON /RL LIMITED /TN \"{_taskName}\" /TR \"\\\"{_executablePath}\\\"\"";
        var (exitCode, output) = Run(args);
        if (exitCode != 0)
            throw new InvalidOperationException($"Could not create the autostart task '{_taskName}' (schtasks exited {exitCode}): {output}");
    }

    /// <summary>Removes the logon task if present. A no-op (not an error) if it does not
    /// exist, so callers can always call this defensively (e.g. test cleanup).</summary>
    public void Disable()
    {
        if (!IsEnabled) return;

        var (exitCode, output) = Run($"/Delete /F /TN \"{_taskName}\"");
        if (exitCode != 0)
            throw new InvalidOperationException($"Could not remove the autostart task '{_taskName}' (schtasks exited {exitCode}): {output}");
    }

    private int Query() => Run($"/Query /TN \"{_taskName}\"").ExitCode;

    private static (int ExitCode, string Output) Run(string arguments)
    {
        var psi = new ProcessStartInfo("schtasks.exe", arguments)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };

        using var process = Process.Start(psi)
            ?? throw new InvalidOperationException("Could not start schtasks.exe.");

        var stdout = process.StandardOutput.ReadToEnd();
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit();

        return (process.ExitCode, string.IsNullOrWhiteSpace(stdout) ? stderr : stdout);
    }
}
