package network.reticulum.transport

/**
 * Is told when the ways [Transport] knows to reach a destination change.
 *
 * Register it with [Transport.addRouteListener] and remove it with
 * [Transport.removeRouteListener]. Python's Reticulum has no such listener. See
 * `port-deviations.md`.
 */
fun interface RouteListener {

    /**
     * Called after one of three things happened to a destination. An announce from it was
     * recorded for [Transport.heardInterfaces]. Its path was stored. Its path was dropped.
     *
     * It runs on a thread that [Transport] keeps for listeners. [Transport] holds none of its
     * locks during the call. Several changes to one destination in quick succession may give one
     * call. No call is made when [Transport.stop] clears the path table.
     *
     * @param destinationHash hash of the destination that changed. The array is a copy.
     */
    fun onRouteChanged(destinationHash: ByteArray)
}
