package network.reticulum.transport

import network.reticulum.common.InterfaceMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Dropping the routes a switched-off carrier left behind, and nothing else.
 *
 * The distinction is the whole value. A route learned over an interface that is no longer carrying
 * is not a slow route but no route at all, and it keeps being chosen because it is fewer hops than
 * the working ones. Expiring every route instead — the obvious version — throws away a direct
 * one-hop LAN path and lets the next path response from a relay take its place, which is a worse
 * outcome than the problem it was meant to fix.
 */
class DeadPathSweepTest {

    private val live = FakeInterface("live0", byteArrayOf(1), online = true)
    private val dead = FakeInterface("dead0", byteArrayOf(2), online = false)

    /**
     * Registered and taken away again, rather than clearing the transport.
     *
     * `Transport` is a singleton shared by every test in this module, so a test that empties its
     * interface list breaks whichever test runs next — which is exactly what the first version of
     * this did.
     */
    @BeforeEach
    fun register() {
        Transport.registerInterface(live)
        Transport.registerInterface(dead)
    }

    @AfterEach
    fun deregister() {
        Transport.deregisterInterface(live)
        Transport.deregisterInterface(dead)
    }

    @Test
    fun `expireDeadPaths should drop a path whose interface is offline`() {
        // given
        val destination = ByteArray(16) { 7 }
        Transport.recordPathForTest(destination, dead.hash)

        // when
        Transport.expireDeadPaths()

        // then
        assertFalse(Transport.hasPath(destination))
    }

    @Test
    fun `expireDeadPaths should keep a path whose interface is carrying`() {
        // given
        val destination = ByteArray(16) { 8 }
        Transport.recordPathForTest(destination, live.hash)

        // when
        Transport.expireDeadPaths()

        // then
        assertTrue(Transport.hasPath(destination), "a working route must survive a carrier change")
    }

}

/**
 * Dropping the route to one peer that has left a carrier the carrier itself is still running.
 *
 * The case the sweep cannot see. A peer that switches its local carrier off keeps its LAN sockets
 * open, so from here the interface is online and the route through it looks perfect — right up
 * until the packets go into a socket whose far end drops them. Only the host knows the peer has
 * gone, and this is the narrowest thing it can act on.
 */
class PathExpiryOnInterfaceTest {

    private val live = FakeInterface("live0", byteArrayOf(1), online = true)
    private val other = FakeInterface("other0", byteArrayOf(2), online = true)

    @BeforeEach
    fun register() {
        Transport.registerInterface(live)
        Transport.registerInterface(other)
    }

    @AfterEach
    fun deregister() {
        Transport.deregisterInterface(live)
        Transport.deregisterInterface(other)
    }

    @Test
    fun `expirePathOn should drop the route when it was learned on that interface`() {
        // given
        val destination = ByteArray(16) { 3 }
        Transport.recordPathForTest(destination, live.hash)

        // when
        val dropped = Transport.expirePathOn(destination, live.hash)

        // then
        assertTrue(dropped)
        assertFalse(Transport.hasPath(destination))
    }

    @Test
    fun `expirePathOn should keep a route learned somewhere else`() {
        // given
        val destination = ByteArray(16) { 4 }
        Transport.recordPathForTest(destination, other.hash)

        // when
        val dropped = Transport.expirePathOn(destination, live.hash)

        // then
        assertFalse(dropped, "a peer leaving one carrier says nothing about the rest")
        assertTrue(Transport.hasPath(destination))
    }

    @Test
    fun `expirePathOn should report nothing when there is no route at all`() {
        // then
        assertFalse(Transport.expirePathOn(ByteArray(16) { 5 }, live.hash))
    }
}

/** Only the three members these tests read mean anything; the rest are the contract's price. */
private class FakeInterface(
    override val name: String,
    override val hash: ByteArray,
    override val online: Boolean,
) : InterfaceRef {
    override val canSend: Boolean = true
    override val canReceive: Boolean = true
    override val mode: InterfaceMode = InterfaceMode.FULL
    override var tunnelId: ByteArray? = null
    override var wantsTunnel: Boolean = false

    override fun send(data: ByteArray) = Unit
}
