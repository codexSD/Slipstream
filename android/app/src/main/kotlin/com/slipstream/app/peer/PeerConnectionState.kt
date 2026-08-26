package com.slipstream.app.peer

/**
 * The five states the app's one control connection to its paired peer can be in. Mirrors
 * `Slipstream.App.Services.IPeerHost.State` (`PeerHost.cs`) — see that file's remarks for why
 * a network switch (spec §5) is represented as [Lost], never as an error/exception.
 */
enum class PeerConnectionState { Idle, Searching, Connected, Degraded, Lost }

/**
 * What the UI needs to render connection status, without reaching into [PeerController]'s
 * internals. [band]/[strategy] mirror `PeerHost.Band`/`PeerHost.DiscoveryStrategy` — `:core`
 * does not currently expose Wi-Fi link-quality metrics anywhere, so [band] stays null until it
 * does (same limitation noted on the Windows sibling).
 */
data class PeerStatus(
    val state: PeerConnectionState,
    val peerName: String? = null,
    val band: String? = null,
    val strategy: String? = null,
)
