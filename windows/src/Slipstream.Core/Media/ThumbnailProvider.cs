using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Media;

/// <summary>
/// Spec §9. Generated on the owning device via the Windows shell thumbnail provider,
/// which covers video, documents, and images uniformly — anything with a registered
/// handler. Cached on disk by (path, mtime, size).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class ThumbnailProvider(string cacheDirectory, TokenVault vault)
{
    public const int LongEdgePixels = 256;

    private readonly ConcurrentDictionary<Guid, string> _tokens = new();

    public string? Generate(string path)
    {
        if (!File.Exists(path)) return null;

        Directory.CreateDirectory(cacheDirectory);

        var info = new FileInfo(path);
        var cached = Path.Combine(cacheDirectory, CacheKey(info) + ".jpg");

        if (File.Exists(cached)) return cached;

        try
        {
            using var bitmap = ShellThumbnail.Get(path, LongEdgePixels);
            if (bitmap is null) return null;

            // Write to a temp name then move, so a concurrent reader never sees a
            // half-written JPEG under the cache key.
            var staging = cached + "." + Guid.NewGuid().ToString("N")[..8] + ".tmp";
            bitmap.Save(staging, ImageFormat.Jpeg);

            try
            {
                File.Move(staging, cached, overwrite: false);
            }
            catch (IOException)
            {
                File.Delete(staging); // another thread won the race; its file is equivalent
            }

            return File.Exists(cached) ? cached : null;
        }
        catch (Exception)
        {
            // No registered handler, a corrupt file, or a COM failure. No thumbnail is
            // a normal outcome — the UI shows a neutral placeholder.
            return null;
        }
    }

    public Guid? TokenFor(string path)
    {
        var thumbnail = Generate(path);
        if (thumbnail is null) return null;

        var token = vault.IssueMedia(thumbnail, new FileInfo(thumbnail).Length);
        _tokens[token.Value] = thumbnail;

        return token.Value;
    }

    public string? Resolve(Guid token) =>
        _tokens.TryGetValue(token, out var path) && File.Exists(path) ? path : null;

    private static string CacheKey(FileInfo info)
    {
        var material = $"{info.FullName}|{info.LastWriteTimeUtc.Ticks}|{info.Length}";
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(material)))[..32].ToLowerInvariant();
    }
}

[SupportedOSPlatform("windows")]
internal static class ShellThumbnail
{
    private const int SIIGBF_RESIZETOFIT = 0x00000000;

    public static Bitmap? Get(string path, int size)
    {
        var factoryGuid = typeof(IShellItemImageFactory).GUID;

        if (SHCreateItemFromParsingName(path, IntPtr.Zero, factoryGuid, out var factory) != 0)
            return null;

        try
        {
            factory.GetImage(new SIZE { cx = size, cy = size }, SIIGBF_RESIZETOFIT, out var handle);
            if (handle == IntPtr.Zero) return null;

            try
            {
                // Copy out of the shell's bitmap before releasing its handle.
                using var shellBitmap = Image.FromHbitmap(handle);
                return new Bitmap(shellBitmap);
            }
            finally
            {
                DeleteObject(handle);
            }
        }
        catch (COMException)
        {
            return null; // no handler for this type
        }
        finally
        {
            Marshal.ReleaseComObject(factory);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SIZE
    {
        public int cx;
        public int cy;
    }

    [ComImport]
    [Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemImageFactory
    {
        void GetImage(SIZE size, int flags, out IntPtr bitmap);
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    private static extern int SHCreateItemFromParsingName(
        string path, IntPtr bindContext, in Guid riid, out IShellItemImageFactory factory);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr handle);
}
