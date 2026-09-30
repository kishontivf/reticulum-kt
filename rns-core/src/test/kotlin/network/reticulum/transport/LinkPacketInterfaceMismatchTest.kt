package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.packet.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins Python `Transport.py:2574-2594` for a packet addressed to one of our own
 * links: a copy that arrives on another interface than the link's is skipped,
 * and its hash is taken off both filter lists, so the copy on the link's own
 * interface is delivered instead of being filtered as a duplicate.
 *
 * Our peers send the same packet over several connections at once (WebRTC,
 * Wi-Fi, Bluetooth). Before the fix the first copy was skipped as a mismatch
 * and every later copy was "FILTERED", so a link's RTT packet never reached it
 * and the link never became active.
 *
 * Uses the inline [CapturingInterface] pattern from
 * [TransportOutboundHeaderTypeTest].
 */
@DisplayName("Transport delivery of a link packet that arrives on another interface")
class LinkPacketInterfaceMismatchTest {

    private class CapturingInterface(
        override val name: String,
        fill: Int,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { fill.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    private lateinit var linkIface: CapturingInterface
    private lateinit var otherIface: CapturingInterface
    private lateinit var link: Link
    private val delivered = mutableListOf<Packet>()

    @BeforeEach
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort — a prior test may have left things in an odd state.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)

        linkIface = CapturingInterface(name = "link-iface-${System.nanoTime()}", fill = 0xAA)
        otherIface = CapturingInterface(name = "other-iface-${System.nanoTime()}", fill = 0xBB)
        Transport.registerInterface(linkIface)
        Transport.registerInterface(otherIface)

        link = receivingLinkAttachedTo(linkIface)
        link.inboundTapForTest = { delivered.add(it) }
        delivered.clear()
    }

    @AfterEach
    fun teardown() {
        link.inboundTapForTest = null
        try {
            Transport.deregisterInterface(linkIface)
            Transport.deregisterInterface(otherIface)
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /**
     * A receiving link whose request came in on [iface], the way
     * `Transport.processLinkRequest` builds one: [Link.validateRequest] attaches
     * the link to the request's interface and registers it as active.
     */
    private fun receivingLinkAttachedTo(iface: CapturingInterface): Link {
        val owner = Destination.create(
            identity = Identity.create(),
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "mismatchtest",
            aspects = arrayOf("link"),
        )
        val crypto = defaultCryptoProvider()
        val requestData = crypto.generateX25519KeyPair().publicKey + crypto.generateEd25519KeyPair().publicKey
        val request = Packet.createRaw(
            destinationHash = owner.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE,
        )
        request.pack()
        request.receivingInterfaceHash = iface.hash

        return checkNotNull(Link.validateRequest(owner, requestData, request)) { "link request must validate" }
    }

    /** The wire bytes of a DATA packet for [link], and the hash Transport files it under. */
    private fun linkPacket(): Pair<ByteArray, ByteArray> {
        val packet = Packet.createRaw(
            destinationHash = link.linkId,
            data = link.encrypt(ByteArray(8) { 0x11 }),
            packetType = PacketType.DATA,
            destinationType = DestinationType.LINK,
            context = PacketContext.NONE,
            createReceipt = false,
        )
        val raw = packet.pack()
        val hash = checkNotNull(Packet.unpack(raw)).packetHash

        return raw to hash
    }

    @Test
    @DisplayName("a copy on another interface is skipped and forgotten, so the copy on the link's interface is delivered")
    fun copyOnOtherInterfaceIsForgottenAndCopyOnLinkInterfaceIsDelivered() {
        val (raw, hash) = linkPacket()

        Transport.inbound(raw, otherIface)

        delivered.size shouldBe 0
        // Pre-fix: the hash stayed remembered and the next copy was FILTERED.
        Transport.packetHashlistContainsForTest(hash) shouldBe false

        Transport.inbound(raw, linkIface)

        delivered.size shouldBe 1
    }

    @Test
    @DisplayName("a packet on the link's interface is delivered and remembered")
    fun packetOnLinkInterfaceIsDeliveredAndRemembered() {
        val (raw, hash) = linkPacket()

        Transport.inbound(raw, linkIface)

        delivered.size shouldBe 1
        Transport.packetHashlistContainsForTest(hash) shouldBe true
    }

    @Test
    @DisplayName("a true duplicate on the link's interface is still filtered")
    fun duplicateOnLinkInterfaceIsStillFiltered() {
        val (raw, _) = linkPacket()

        Transport.inbound(raw, linkIface)
        Transport.inbound(raw, linkIface)

        delivered.size shouldBe 1
    }
}
