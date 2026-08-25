using System.Runtime.Versioning;

// This benchmark drives TwoPeers (Slipstream.Core.Tests), which drives SlipstreamPeer
// — itself [SupportedOSPlatform("windows")]. Nothing references this assembly, so
// declaring the whole executable Windows-only here is simpler than guarding every
// call site in Program.cs's top-level statements.
[assembly: SupportedOSPlatform("windows")]
