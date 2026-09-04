package network.reticulum.interfaces

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Turning an interface off without taking it down.
 *
 * `detach` is the existing way to stop an interface and it is one-way — the flag is never cleared —
 * so it cannot serve an operator switching a carrier off to see what happens and back on again.
 * This is the reversible half, and it sits *above* what the interface reports about itself: that is
 * the whole point, since a TCP client that reconnects while disabled would otherwise put itself
 * back into service.
 */
class InterfaceEnabledTest {

    private val iface = object : Interface("test0") {
        override fun processOutgoing(data: ByteArray) = Unit
        override fun start() = Unit

        fun receive(data: ByteArray) = processIncoming(data)
    }

    @Test
    fun `isEnabled should be true before anything disables the interface`() {
        // then
        assertTrue(iface.isEnabled)
    }

    @Test
    fun `online should be false while the interface is disabled`() {
        // given
        iface.setOnline(true)

        // when
        iface.setEnabled(false)

        // then
        assertFalse(iface.online.value, "Transport skips an offline interface on every send path")
    }

    /** The leak this exists to close: a socket that comes back must not put itself into service. */
    @Test
    fun `online should stay false when the interface reconnects while disabled`() {
        // given
        iface.setEnabled(false)

        // when
        iface.setOnline(true)

        // then
        assertFalse(iface.online.value)
    }

    @Test
    fun `online should return to what the interface reports when it is enabled again`() {
        // given
        iface.setOnline(true)
        iface.setEnabled(false)

        // when
        iface.setEnabled(true)

        // then
        assertTrue(iface.online.value, "and nothing had to be reconnected by hand")
    }

    @Test
    fun `online should stay false when a disconnected interface is enabled again`() {
        // given
        iface.setOnline(false)
        iface.setEnabled(false)

        // when
        iface.setEnabled(true)

        // then
        assertFalse(iface.online.value, "enabling is not connecting")
    }

    @Test
    fun `processIncoming should drop what arrives while the interface is disabled`() {
        // given
        val received = mutableListOf<ByteArray>()
        iface.onPacketReceived = { data, _ -> received.add(data) }
        iface.setOnline(true)
        iface.setEnabled(false)

        // when
        iface.receive(byteArrayOf(1, 2, 3))

        // then
        assertTrue(received.isEmpty(), "a disabled interface must not carry traffic in either direction")
    }
}

/**
 * The adapter is the choke point every transport-initiated send passes through.
 *
 * `Transport` filters on `online` before most sends, but a destination whose route is already in
 * the path table is transmitted without that check — so a disabled interface has to be stopped here
 * too, or switching one off leaves cached routes still flowing over it.
 */
class InterfaceAdapterEnabledTest {

    private val sent = mutableListOf<ByteArray>()

    private val iface = object : Interface("test0") {
        override fun processOutgoing(data: ByteArray) {
            sent.add(data)
        }

        override fun start() = Unit
    }

    @Test
    fun `send should reach the interface while it is enabled`() {
        // given
        val ref = iface.toRef()

        // when
        ref.send(byteArrayOf(1, 2, 3))

        // then
        assertTrue(sent.isNotEmpty())
    }

    @Test
    fun `send should be dropped while the interface is disabled`() {
        // given
        val ref = iface.toRef()
        iface.setEnabled(false)

        // when
        ref.send(byteArrayOf(1, 2, 3))

        // then
        assertTrue(sent.isEmpty(), "a cached route must not keep flowing over a switched-off carrier")
    }
}
