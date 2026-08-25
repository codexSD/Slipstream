using Slipstream.App.Services;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 15: <see cref="AutostartService"/> is backed by a real per-user Task Scheduler logon
/// task (not a Run key — it survives better and can run without a console flash). These tests
/// genuinely create/remove a scheduled task named "Slipstream.Test"/"Slipstream.Test2" via
/// <c>schtasks.exe</c>; creating a per-user logon task does not require elevation, so this
/// works under a normal test-runner account. Each test cleans up in a <c>finally</c> so no
/// leftover task survives a run, whether it passed or failed.
/// </summary>
public class AutostartServiceTests
{
    [Fact]
    public void Enabling_autostart_writes_a_logon_task_and_disabling_removes_it()
    {
        var service = new AutostartService(taskName: "Slipstream.Test");
        try
        {
            service.Enable();
            Assert.True(service.IsEnabled);
            service.Disable();
            Assert.False(service.IsEnabled);
        }
        finally { service.Disable(); }
    }

    [Fact]
    public void Enabling_twice_is_idempotent()
    {
        var service = new AutostartService("Slipstream.Test2");
        try { service.Enable(); service.Enable(); Assert.True(service.IsEnabled); }
        finally { service.Disable(); }
    }
}
