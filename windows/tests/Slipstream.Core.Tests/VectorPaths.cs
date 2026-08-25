namespace Slipstream.Core.Tests;

public static class VectorPaths
{
    public static string Root { get; } = Locate();

    private static string Locate()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "vectors")))
            dir = dir.Parent;

        return dir is null
            ? throw new DirectoryNotFoundException("Could not locate protocol/vectors from the test output directory.")
            : Path.Combine(dir.FullName, "protocol", "vectors");
    }
}
