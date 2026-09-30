package network.reticulum.resource

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
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the packets a Resource sends to end a transfer, mirroring python:
 *
 *  - `Resource.reject` sends `RESOURCE_RCL` over the link with the resource
 *    hash as its data (Resource.py:156-166). Before the fix it addressed the
 *    packet to the 32-byte resource hash and threw "Destination hash must be
 *    16 bytes", so the sender was never told and waited for its own timeout;
 *  - a cancelling receiver sends `RESOURCE_RCL` and a cancelling initiator
 *    sends `RESOURCE_ICL` (Resource.py:1103-1116). Before the fix only the
 *    initiator's side was sent.
 *
 * One receiving link stands in for both ends: its key encrypts and decrypts
 * both ways, and Transport routes its packets to the inline
 * [CapturingInterface] (the pattern from [TransportOutboundHeaderTypeTest]).
 */
@DisplayName("Resource reject and cancel packets")
class ResourceRejectAndCancelTest {

    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAA.toByte() }
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

    private lateinit var iface: CapturingInterface
    private lateinit var link: Link

    @BeforeEach
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort — a prior test may have left things in an odd state.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)
        // A watchdog firing mid-test would cancel a Resource under us.
        Resource.watchdogDisabledForTest = true

        iface = CapturingInterface(name = "capture-${System.nanoTime()}")
        Transport.registerInterface(iface)

        link = activeReceivingLink()
        iface.sent.clear()
    }

    @AfterEach
    fun teardown() {
        Resource.watchdogDisabledForTest = false
        try {
            Transport.deregisterInterface(iface)
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
     * A receiving link with derived keys, attached to [iface] and forced ACTIVE.
     * [Link.validateRequest] runs the handshake, registers the link's path on the
     * request's interface, and sends the proof, which the test discards.
     */
    private fun activeReceivingLink(): Link {
        val owner = Destination.create(
            identity = Identity.create(),
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "rejecttest",
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

        val link = checkNotNull(Link.validateRequest(owner, requestData, request)) { "link request must validate" }
        link.setStatusForTest(LinkConstants.ACTIVE)

        return link
    }

    /** An initiator-side Resource on [link] that has not been advertised. */
    private fun outgoingResource(): Resource =
        Resource.create(data = ByteArray(64) { 0x5A }, link = link, advertise = false)

    /** Every packet [iface] captured, unpacked, with the given [context]. */
    private fun sentWithContext(context: PacketContext): List<Packet> =
        iface.sent.mapNotNull { Packet.unpack(it) }.filter { it.context == context }

    /** Asserts [packet] is a link packet to [link] whose data decrypts to [resourceHash]. */
    private fun assertCarriesHashOverLink(packet: Packet, resourceHash: ByteArray) {
        packet.packetType shouldBe PacketType.DATA
        packet.destinationType shouldBe DestinationType.LINK
        packet.destinationHash.contentEquals(link.linkId) shouldBe true
        checkNotNull(link.decrypt(packet.data)).contentEquals(resourceHash) shouldBe true
    }

    @Test
    @DisplayName("reject sends a RESOURCE_RCL over the link carrying the resource hash")
    fun rejectSendsRclOverLinkWithResourceHash() {
        val advertisement = ResourceAdvertisement.fromResource(outgoingResource())

        Resource.reject(advertisement, link)

        // Pre-fix: createRaw threw on the 32-byte destination and nothing was sent.
        val rcls = sentWithContext(PacketContext.RESOURCE_RCL)
        rcls.size shouldBe 1
        assertCarriesHashOverLink(rcls[0], advertisement.hash)
    }

    @Test
    @DisplayName("a sender that receives the RESOURCE_RCL ends its outgoing resource")
    fun senderReceivingRclEndsOutgoingResource() {
        val sender = outgoingResource()
        link.registerOutgoingResource(sender)
        var concluded: Resource? = null
        sender.callbacks.failed = { concluded = it }
        Resource.reject(ResourceAdvertisement.fromResource(sender), link)
        val rcl = sentWithContext(PacketContext.RESOURCE_RCL).single()

        link.receive(rcl)

        link.hasOutgoingResource(sender) shouldBe false
        (concluded === sender) shouldBe true
        // Terminal. processResourceRcl ends it through cancel(), so the status is
        // FAILED rather than python's REJECTED.
        (sender.status >= ResourceConstants.COMPLETE) shouldBe true
    }

    @Test
    @DisplayName("a receiver's cancel sends RESOURCE_RCL")
    fun receiverCancelSendsRcl() {
        val receiver = checkNotNull(Resource.accept(ResourceAdvertisement.fromResource(outgoingResource()), link))
        iface.sent.clear()

        receiver.cancel()

        // Pre-fix: nothing was sent for a receiver.
        val rcls = sentWithContext(PacketContext.RESOURCE_RCL)
        rcls.size shouldBe 1
        assertCarriesHashOverLink(rcls[0], receiver.hash)
        sentWithContext(PacketContext.RESOURCE_ICL).size shouldBe 0
    }

    @Test
    @DisplayName("a sender's cancel still sends RESOURCE_ICL")
    fun senderCancelStillSendsIcl() {
        val sender = outgoingResource()
        iface.sent.clear()

        sender.cancel()

        val icls = sentWithContext(PacketContext.RESOURCE_ICL)
        icls.size shouldBe 1
        assertCarriesHashOverLink(icls[0], sender.hash)
        sentWithContext(PacketContext.RESOURCE_RCL).size shouldBe 0
    }
}
