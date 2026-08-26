using Slipstream.App.Services;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 15: <see cref="AutostartService"/> is backed by a real per-user Task Scheduler logon
/// task (not a Run key — it survives better and can run without a console flash).
/// </summary>
/// <remarks>
/// These tests used to create real tasks named "Slipstream.Test"/"Slipstream.Test2" via
/// <c>schtasks.exe</c>, on the assumption that a per-user <c>/RL LIMITED</c> logon task needs
/// no elevation. That assumption is false on machines whose policy restricts logon-triggered
/// tasks in the root task folder: <c>schtasks /Create /SC ONLOGON</c> returns
/// <c>ERROR: Access is denied.</c> (exit 1) for a standard user, and both tests failed in
/// ~100 ms for reasons having nothing to do with the code under test.
///
/// They are now driven through <see cref="SchtasksRunner"/>, a substituted Task Scheduler
/// boundary. That is deliberately not a skip: every assertion below still runs on every
/// machine, elevated or not, and they pin more than the originals did — the exact
/// <c>schtasks</c> command line for each operation, the idempotency contract, the live-query
/// semantics of <see cref="AutostartService.IsEnabled"/>, and the error path when the
/// scheduler refuses. What is no longer covered is whether the *current account* may register
/// a logon task, which is a property of the machine rather than of this code.
/// </remarks>
public class AutostartServiceTests
{
    private const string Exe = @"C:\Apps\Slipstream\Slipstream.App.exe";

    [Fact]
    public void Enabling_autostart_writes_a_logon_task_and_disabling_removes_it()
    {
        var scheduler = new FakeScheduler();
        var service = new AutostartService("Slipstream.Test", Exe, scheduler.Run);

        Assert.False(service.IsEnabled);

        service.Enable();
        Assert.True(service.IsEnabled);
        Assert.Equal(Exe, Assert.Contains("Slipstream.Test", scheduler.Tasks));

        service.Disable();
        Assert.False(service.IsEnabled);
        Assert.Empty(scheduler.Tasks);
    }

    [Fact]
    public void Enabling_registers_a_limited_onlogon_task_for_the_given_executable()
    {
        var scheduler = new FakeScheduler();
        new AutostartService("Slipstream.Test", Exe, scheduler.Run).Enable();

        // The literal command line matters: /SC ONLOGON is the plan §14 trigger, /RL LIMITED
        // keeps the task unelevated, /F makes re-registration overwrite rather than fail, and
        // the executable path must stay quoted so a path with spaces is not split into args.
        Assert.Equal(
            "/Create /F /SC ONLOGON /RL LIMITED /TN \"Slipstream.Test\" /TR \"\\\"" + Exe + "\\\"\"",
            scheduler.Commands[0]);
    }

    [Fact]
    public void Enabling_twice_is_idempotent()
    {
        var scheduler = new FakeScheduler();
        var service = new AutostartService("Slipstream.Test2", Exe, scheduler.Run);

        service.Enable();
        service.Enable();

        Assert.True(service.IsEnabled);
        Assert.Equal("Slipstream.Test2", Assert.Single(scheduler.Tasks).Key);
    }

    [Fact]
    public void Disabling_a_task_that_is_not_registered_does_not_call_the_scheduler()
    {
        var scheduler = new FakeScheduler();

        new AutostartService("Slipstream.Test", Exe, scheduler.Run).Disable();

        Assert.DoesNotContain(scheduler.Commands, c => c.StartsWith("/Delete", StringComparison.Ordinal));
    }

    [Fact]
    public void A_scheduler_that_refuses_the_registration_surfaces_its_message()
    {
        // The exact failure this suite used to hit on an unelevated account. It must arrive as
        // a loud, legible exception naming the task, the exit code and the scheduler's own
        // words — never as a silently unregistered autostart.
        static (int, string) Denied(string arguments) =>
            arguments.StartsWith("/Create", StringComparison.Ordinal)
                ? (1, "ERROR: Access is denied.")
                : (1, "ERROR: The system cannot find the file specified.");

        var error = Assert.Throws<InvalidOperationException>(
            () => new AutostartService("Slipstream.Test", Exe, Denied).Enable());

        Assert.Contains("Slipstream.Test", error.Message, StringComparison.Ordinal);
        Assert.Contains("schtasks exited 1", error.Message, StringComparison.Ordinal);
        Assert.Contains("Access is denied", error.Message, StringComparison.Ordinal);
    }

    /// <summary>
    /// An in-memory stand-in for <c>schtasks.exe</c> reproducing the three behaviours
    /// <see cref="AutostartService"/> depends on: <c>/Query</c> exits 0 only for a registered
    /// task, <c>/Create /F</c> overwrites in place, and <c>/Delete /F</c> removes.
    /// </summary>
    private sealed class FakeScheduler
    {
        public Dictionary<string, string> Tasks { get; } = new(StringComparer.OrdinalIgnoreCase);

        public List<string> Commands { get; } = [];

        public (int ExitCode, string Output) Run(string arguments)
        {
            Commands.Add(arguments);

            var name = Between(arguments, "/TN \"", "\"");

            if (arguments.StartsWith("/Query", StringComparison.Ordinal))
                return Tasks.ContainsKey(name) ? (0, name) : (1, "ERROR: The system cannot find the file specified.");

            if (arguments.StartsWith("/Create", StringComparison.Ordinal))
            {
                Tasks[name] = Between(arguments, "/TR \"\\\"", "\\\"\"");
                return (0, $"SUCCESS: The scheduled task \"{name}\" has successfully been created.");
            }

            if (arguments.StartsWith("/Delete", StringComparison.Ordinal))
                return Tasks.Remove(name) ? (0, "") : (1, "ERROR: The system cannot find the file specified.");

            return (1, $"ERROR: Invalid argument/option - '{arguments}'.");
        }

        private static string Between(string value, string start, string end)
        {
            var from = value.IndexOf(start, StringComparison.Ordinal) + start.Length;
            var to = value.IndexOf(end, from, StringComparison.Ordinal);
            return value[from..to];
        }
    }
}
