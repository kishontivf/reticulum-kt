package network.reticulum.transport

import network.reticulum.common.InterfaceMode
import network.reticulum.common.toKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The fallback-interface tier: a normal interface out-ranks a fallback one for the same
 * destination regardless of hop count, and the fallback takes the route over only once the normal
 * path is provably not delivering — silent past the grace, failing to deliver, or pinned away.
 *
 * Ported behaviour from reticulum-swift's `PathTable`, which is what the iOS build routes with;
 * the two implementations have to agree or an iPhone and an Android phone pick different carriers
 * for the same conversation.
 */
class FallbackTierTest {

    private val tcp = FallbackFakeInterface("tcpTest0", byteArrayOf(101), online = true)
    private val tcpOther = FallbackFakeInterface("tcpTest1", byteArrayOf(102), online = true)
    private val deadTcp = FallbackFakeInterface("deadTcpTest0", byteArrayOf(103), online = false)
    private val carrier = FallbackFakeInterface("fbTest0", byteArrayOf(104), online = true)

    private lateinit var destination: ByteArray

    /**
     * Registered and taken away again rather than clearing the transport — `Transport` is a
     * singleton shared by every test in this module. The destination is unique per test for the
     * same reason: the last-heard bookkeeping survives in the singleton, and a fresh record left
     * by one test must not decide another's verdict.
     */
    @BeforeEach
    fun register() {
        val sequence = nextDestination++

        destination = ByteArray(16) { 0x51 }
        destination[0] = sequence.toByte()
        destination[1] = (sequence shr 8).toByte()

        Transport.registerInterface(tcp)
        Transport.registerInterface(tcpOther)
        Transport.registerInterface(deadTcp)
        Transport.registerInterface(carrier)
        Transport.setFallbackInterface(carrier.name)
    }

    @AfterEach
    fun deregister() {
        Transport.expirePath(destination)
        Transport.setDestinationPinnedToFallback(destination, false)
        Transport.setFallbackInterface(carrier.name, isFallback = false)
        Transport.deregisterInterface(tcp)
        Transport.deregisterInterface(tcpOther)
        Transport.deregisterInterface(deadTcp)
        Transport.deregisterInterface(carrier)
    }

    @Test
    fun `sendFallbackCopy should send a copy over a carrier the peer was heard on`() {
        // given
        // The measured case: two phones one hop apart, the route pointing three hops away through
        // somebody else's relay, and every packet down it lost while the direct link sat idle.
        Transport.recordPathForTest(destination, tcp.hash)
        Transport.setFallbackLastHeardForTest(destination, carrier.name, System.currentTimeMillis())

        // when
        Transport.sendFallbackCopy(destination, PACKET)

        // then
        assertEquals(1, carrier.sent.size)
        assertTrue(carrier.sent.single().contentEquals(PACKET))
    }

    @Test
    fun `sendFallbackCopy should send nothing when the peer was not heard on the carrier`() {
        // given
        Transport.recordPathForTest(destination, tcp.hash)

        // when
        Transport.sendFallbackCopy(destination, PACKET)

        // then
        // A carrier the peer has not announced on reaches nobody, and the copy is only ever worth
        // one packet because the peer is known to be within reach of it.
        assertTrue(carrier.sent.isEmpty())
    }

    @Test
    fun `sendFallbackCopy should send nothing when the route already runs directly over the carrier`() {
        // given
        Transport.recordPathForTest(destination, carrier.hash)
        Transport.setFallbackLastHeardForTest(destination, carrier.name, System.currentTimeMillis())

        // when
        Transport.sendFallbackCopy(destination, PACKET)

        // then
        // Otherwise the same link carries the message twice, which is cost with no second chance.
        assertTrue(carrier.sent.isEmpty())
    }

    @Test
    fun `sendFallbackCopy should still send a copy when the carrier route is relayed`() {
        // given
        // The measured failure, and the one the hop check exists for: two phones one hop apart,
        // with the route between them pointing three hops away through somebody else's relay
        // because that announce arrived first. Every packet down it was lost for nineteen seconds
        // while the direct link sat idle.
        Transport.recordPathForTest(destination, carrier.hash, hops = 3)
        Transport.setFallbackLastHeardForTest(destination, carrier.name, System.currentTimeMillis())

        // then
        assertFalse(
            Transport.isBestPathDirectOnFallback(destination),
            "a three-hop route is addressed to a relay, so a direct copy is a different packet",
        )

        // when
        Transport.sendFallbackCopy(destination, PACKET)

        // then
        assertEquals(1, carrier.sent.size)
    }

    @Test
    fun `sendFallbackCopy should send nothing when the peer was heard too long ago`() {
        // given
        Transport.recordPathForTest(destination, tcp.hash)
        Transport.setFallbackLastHeardForTest(destination, carrier.name, System.currentTimeMillis() - STALE)

        // when
        Transport.sendFallbackCopy(destination, PACKET)

        // then
        assertTrue(carrier.sent.isEmpty())
    }

    @Test
    fun `isBestPathDirectOnFallback should refuse a relayed route over a carrier`() {
        // given
        Transport.recordPathForTest(destination, carrier.hash, hops = 2)

        // then
        assertTrue(Transport.isBestPathFallback(destination))
        assertFalse(Transport.isBestPathDirectOnFallback(destination))
    }

    @Test
    fun `isBestPathFallback should say whether the route already runs over a carrier`() {
        // given
        Transport.recordPathForTest(destination, carrier.hash)

        // then
        assertTrue(Transport.isBestPathFallback(destination))

        // when
        Transport.recordPathForTest(destination, tcp.hash)

        // then
        assertFalse(Transport.isBestPathFallback(destination))
    }

    @Test
    fun `arbitrateFallbackAdmission should reject a carrier announce while the normal interface is connected`() {
        // given
        Transport.recordPathForTest(destination, tcp.hash)

        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.REJECT, verdict)
    }

    @Test
    fun `arbitrateFallbackAdmission should reject a carrier announce when the normal path was heard within the grace`() {
        // given
        Transport.recordPathForTest(destination, deadTcp.hash)
        backdatePath(beyondGrace = true)
        Transport.setFallbackLastHeardForTest(destination, deadTcp.name, System.currentTimeMillis() - 10_000)

        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.REJECT, verdict)
    }

    @Test
    fun `arbitrateFallbackAdmission should let the carrier take over once the normal path has gone silent past the grace`() {
        // given
        Transport.recordPathForTest(destination, deadTcp.hash)
        backdatePath(beyondGrace = true)
        Transport.setFallbackLastHeardForTest(destination, deadTcp.name, beyondGraceTimestamp())

        // when
        val verdict = arbitrate(candidate = carrier)

        // then — UNDECIDED, not an outright accept: the regular admission tree still applies, so
        // only the carrier's own fresh announce can take the route, never a stale duplicate.
        assertEquals(Transport.FallbackArbitration.UNDECIDED, verdict)
    }

    /**
     * The startup window: an entry recorded moments ago has had no chance to accumulate last-heard
     * records, and retiring it before one announce interval has even passed would hand every fresh
     * route to the carrier.
     */
    @Test
    fun `arbitrateFallbackAdmission should keep a fresh normal path before anything has been heard`() {
        // given — deadTcp is offline, so neither connectivity nor recency speaks for it
        Transport.recordPathForTest(destination, deadTcp.hash)

        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.REJECT, verdict)
    }

    /**
     * Delivery failure out-ranks every liveness signal. A peer behind carrier NAT resolves a
     * multi-hop TCP path that never delivers — connected and announce-fresh the whole time — and
     * keeping it blocks the one carrier that works.
     */
    @Test
    fun `arbitrateFallbackAdmission should let the carrier take over when the path is failing to deliver`() {
        // given — connected AND fresh, so only the failure signal can explain the verdict
        Transport.recordPathForTest(destination, tcp.hash)
        Transport.markPathUnresponsive(destination)

        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.UNDECIDED, verdict)
    }

    /**
     * The promote direction is unconditional. Both links carry the same peer's announces and the
     * direct carrier beats the relayed copy of the very same announce to this device every time —
     * any freshness gate here lets the carrier hold the route indefinitely once it has won it.
     */
    @Test
    fun `arbitrateFallbackAdmission should promote a normal announce over the carrier regardless of freshness`() {
        // given
        Transport.recordPathForTest(destination, carrier.hash)

        // when
        val verdict = arbitrate(candidate = tcp)

        // then
        assertEquals(Transport.FallbackArbitration.PROMOTE, verdict)
    }

    @Test
    fun `arbitrateFallbackAdmission should reject a normal announce while the destination is pinned`() {
        // given
        Transport.setDestinationPinnedToFallback(destination, true)

        // when
        val verdict = arbitrate(candidate = tcp)

        // then
        assertEquals(Transport.FallbackArbitration.REJECT, verdict)
    }

    /**
     * A transport node may keep relaying the pinned peer's dead TCP route; letting those stale
     * copies refresh the liveness signal is exactly what the pin exists to stop.
     */
    @Test
    fun `arbitrateFallbackAdmission should not record a pin-rejected arrival as heard`() {
        // given
        Transport.setDestinationPinnedToFallback(destination, true)

        // when
        arbitrate(candidate = tcp)

        // then
        assertFalse(Transport.wasHeardOnInterface(destination, tcp.name, withinMs = Long.MAX_VALUE))
    }

    @Test
    fun `arbitrateFallbackAdmission should ignore connectivity when the destination is pinned`() {
        // given — a silent-but-connected normal path, which without the pin would win
        Transport.recordPathForTest(destination, tcp.hash)
        backdatePath(beyondGrace = true)
        Transport.setDestinationPinnedToFallback(destination, true)

        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.UNDECIDED, verdict)
    }

    /**
     * A suppressed carrier still counts as heard — otherwise its own claim could never establish
     * once the normal path dies, and the takeover it exists for would never happen.
     */
    @Test
    fun `arbitrateFallbackAdmission should record a rejected carrier arrival as heard`() {
        // given
        Transport.recordPathForTest(destination, tcp.hash)

        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.REJECT, verdict)
        assertTrue(Transport.wasHeardOnInterface(destination, carrier.name, withinMs = 60_000))
    }

    @Test
    fun `arbitrateFallbackAdmission should stand aside when both interfaces are ordinary`() {
        // given
        Transport.recordPathForTest(destination, tcp.hash)

        // when
        val verdict = arbitrate(candidate = tcpOther)

        // then
        assertEquals(Transport.FallbackArbitration.UNDECIDED, verdict)
    }

    @Test
    fun `arbitrateFallbackAdmission should stand aside for an unknown destination`() {
        // when
        val verdict = arbitrate(candidate = carrier)

        // then
        assertEquals(Transport.FallbackArbitration.UNDECIDED, verdict)
    }

    @Test
    fun `wasHeardOnInterface should forget a record older than the window`() {
        // given
        Transport.setFallbackLastHeardForTest(destination, tcp.name, System.currentTimeMillis() - 2_000)

        // then
        assertFalse(Transport.wasHeardOnInterface(destination, tcp.name, withinMs = 1_000))
        assertTrue(Transport.wasHeardOnInterface(destination, tcp.name, withinMs = 10_000))
    }

    @Test
    fun `cullTables should drop last-heard bookkeeping older than the horizon`() {
        // given
        val stale = System.currentTimeMillis() - TransportConstants.FALLBACK_LAST_HEARD_MAX_AGE_MS - 1_000
        Transport.setFallbackLastHeardForTest(destination, tcp.name, stale)

        // when
        Transport.forceCullForTest()

        // then
        assertFalse(Transport.wasHeardOnInterface(destination, tcp.name, withinMs = Long.MAX_VALUE))
    }

    private fun arbitrate(candidate: InterfaceRef): Transport.FallbackArbitration =
        Transport.arbitrateFallbackAdmission(destination, candidate, Transport.pathTable[destination.toKey()])

    /** Ages the entry itself past the takeover grace, as a route that stopped refreshing would be. */
    private fun backdatePath(beyondGrace: Boolean) {
        if (beyondGrace) Transport.setPathTimestampForTest(destination, beyondGraceTimestamp())
    }

    private fun beyondGraceTimestamp(): Long =
        System.currentTimeMillis() - TransportConstants.FALLBACK_TAKEOVER_GRACE_MS - 1_000

    private companion object {

        var nextDestination = 1
    }
}

/** Only name, hash and online mean anything to these tests; the rest is the contract's price. */
private val PACKET = ByteArray(48) { it.toByte() }

/** Comfortably past `FALLBACK_COPY_HEARD_WITHIN`, so the peer counts as gone. */
private const val STALE = 10L * 60 * 1000

private class FallbackFakeInterface(
    override val name: String,
    override val hash: ByteArray,
    override val online: Boolean,
) : InterfaceRef {
    override val canSend: Boolean = true
    override val canReceive: Boolean = true
    override val mode: InterfaceMode = InterfaceMode.FULL
    override var tunnelId: ByteArray? = null
    override var wantsTunnel: Boolean = false

    val sent = mutableListOf<ByteArray>()

    override fun send(data: ByteArray) {
        sent += data
    }
}
