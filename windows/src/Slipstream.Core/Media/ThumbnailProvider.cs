using System.Runtime.Versioning;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Media;

[SupportedOSPlatform("windows")]
public sealed class ThumbnailProvider
{
    public ThumbnailProvider(string cachePath, TokenVault vault)
    {
    }
    
    public string? Generate(string path)
    {
        return null;
    }
    
    public Guid? TokenFor(string path)
    {
        return null;
    }
    
    public string? Resolve(Guid token)
    {
        return null;
    }
}
