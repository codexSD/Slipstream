package com.slipstream.core.discovery

/**
 * The "answer a query while idle" half of discovery, separated from
 * [DiscoveryStrategy.find] (the "go looking for a peer" half) because the two have
 * different lifetimes: [com.slipstream.core.SlipstreamPeer] keeps a responder live for as
 * long as it is running, whereas a find() is a short burst.
 *
 * Spec §5: "the phone only ever listens and responds." A phone that only bound its
 * discovery socket while running its own find() would be invisible to a PC's query for
 * almost all of its uptime.
 */
interface DiscoveryResponder {
    /** Starts (or joins) the always-on responder. Refcounted: safe to call more than once. */
    suspend fun startResponder()

    /** Drops one responder reference; the last one released tears the listener down. */
    suspend fun stopResponder()
}
