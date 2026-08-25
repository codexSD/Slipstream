using System.Runtime.Versioning;

// This tool is a thin, Windows-only CLI over Slipstream.Core (which now exposes
// SlipstreamPeer.StartAsync — itself [SupportedOSPlatform("windows")]). Nothing
// references this assembly, so declaring the whole executable Windows-only here is
// simpler than guarding every call site in Program.cs's top-level statements.
[assembly: SupportedOSPlatform("windows")]
