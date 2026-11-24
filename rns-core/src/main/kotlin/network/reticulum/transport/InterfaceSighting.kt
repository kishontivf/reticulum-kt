package network.reticulum.transport

/**
 * The last announce [Transport] heard from one destination on one interface.
 *
 * Python's Reticulum keeps no such record. See `port-deviations.md`.
 *
 * @param heardAtMs when the announce arrived, in epoch milliseconds.
 * @param hops how many hops away the destination is, as [PathEntry.hops] counts them. A
 *   destination that announced to this node directly has 1. One behind a local shared instance
 *   has 0.
 */
data class InterfaceSighting(
    val heardAtMs: Long,
    val hops: Int,
)
