package network.reticulum.transport

import network.reticulum.common.InterfaceMode

/**
 * Determines whether an announce should be forwarded to a given interface,
 * based on the Python reference implementation (Transport.py:1040-1084).
 */
object AnnounceFilter {

    /**
     * Check whether an announce should be forwarded to an interface with
     * [outgoingMode].
     *
     * @param outgoingMode The mode of the interface we'd send the announce on
     * @param isLocalDestination Whether the destination is hosted on this instance
     * @param sourceMode The mode of the next-hop interface where the announce
     *   was originally received (null if unknown)
     * @return true if the announce should be forwarded
     */
    fun shouldForward(
        outgoingMode: InterfaceMode,
        isLocalDestination: Boolean,
        sourceMode: InterfaceMode?
    ): Boolean {
        return when (outgoingMode) {
            // AP mode: never broadcast announces
            InterfaceMode.ACCESS_POINT -> false

            // ROAMING mode: allow local destinations; block if source is ROAMING or BOUNDARY
            InterfaceMode.ROAMING -> {
                if (isLocalDestination) true
                else when (sourceMode) {
                    InterfaceMode.ROAMING, InterfaceMode.BOUNDARY -> false
                    null -> false
                    else -> true
                }
            }

            // BOUNDARY mode: allow local destinations; block if source is ROAMING
            InterfaceMode.BOUNDARY -> {
                if (isLocalDestination) true
                else when (sourceMode) {
                    InterfaceMode.ROAMING -> false
                    null -> false
                    else -> true
                }
            }

            // FULL, POINT_TO_POINT, GATEWAY: no mode-based restrictions
            else -> true
        }
    }

    /**
     * Get the path expiry duration for a given interface mode.
     * Matches Python Transport.py:1730-1735.
     */
    fun pathExpiryForMode(mode: InterfaceMode): Long {
        return when (mode) {
            InterfaceMode.ACCESS_POINT -> TransportConstants.AP_PATH_TIME
            InterfaceMode.ROAMING -> TransportConstants.ROAMING_PATH_TIME
            else -> TransportConstants.PATHFINDER_E
        }
    }

    /**
     * Whether an arriving announce should be held rather than processed, when the interface it
     * came in on is over its announce bandwidth allocation.
     *
     * Ingress limiting exists to stop an unsolicited announce flood costing this node bandwidth it
     * never asked for, so it applies to announces for destinations it does not already know.
     *
     * **A path response is exempt, and that exemption is load-bearing.** It is the answer to a
     * request this node made moments ago — the one mechanism by which an unknown destination
     * becomes a known one. Holding it defeats path discovery outright: the requester learns
     * nothing, waits out its budget and then sends with no route. A busy relay interface can
     * deliver the same response more than once inside a second, so holding on allocation alone
     * discards every copy and strands the traffic that was waiting on the route.
     *
     * @param isKnownDestination whether the path table already holds this destination
     * @param isPathResponse whether the announce answers a path request rather than arriving
     *   unbidden
     * @param isOverAllocation what the receiving interface says about its announce bandwidth
     */
    fun shouldHold(
        isKnownDestination: Boolean,
        isPathResponse: Boolean,
        isOverAllocation: Boolean
    ): Boolean = !isKnownDestination && !isPathResponse && isOverAllocation
}
