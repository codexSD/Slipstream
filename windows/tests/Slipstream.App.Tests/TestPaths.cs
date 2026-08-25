namespace Slipstream.App.Tests;

public static class TestPaths
{
    public static string Meridian(string relativePath)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "windows", "src", "Slipstream.Meridian")))
            dir = dir.Parent;

        if (dir is null)
            throw new DirectoryNotFoundException("Could not locate windows/src/Slipstream.Meridian from the test output directory.");

        return Path.Combine(dir.FullName, "windows", "src", "Slipstream.Meridian", relativePath);
    }
}
