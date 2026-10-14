package network.reticulum.resource

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.crypto.Hashes
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
import kotlin.random.Random

/**
 * Pins what a sender-side Resource tells a caller about its progress: [Resource.sentParts] and
 * [Resource.lastActivityAt]. LXMF-kt's stall rule reads them to tell a slow transfer from a dead
 * one. So a receiver asking for one part twice must count it once, and a part going out must
 * move the activity time.
 *
 * One receiving link stands in for both ends, as in [ResourceRejectAndCancelTest]: its key
 * encrypts both ways, and Transport routes its packets to the inline [CapturingInterface]. The
 * receiver's part request is played by hand, because nothing here has a peer.
 */
@DisplayName("Resource sender progress accessors")
class ResourceActivityTest {

    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAB.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            // Nothing reads what goes out; the link only has to be able to send.
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

        iface = CapturingInterface(name = "activity-${System.nanoTime()}")
        Transport.registerInterface(iface)

        link = activeReceivingLink()
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

    @Test
    fun `sentParts should count each part once however often it is requested`() {
        // given
        val resource = advertisedResource()

        // when
        requestPart(resource, 0)
        requestPart(resource, 0)
        requestPart(resource, 1)

        // then
        resource.sentParts shouldBe 2
        resource.sentPartsForTest() shouldBe 2
    }

    @Test
    fun `lastActivityAt should move when a part goes out`() {
        // given
        val resource = advertisedResource()
        resource.backdateActivityForTest(A_MINUTE)
        val before = resource.lastActivityAt
        val requestedAt = System.currentTimeMillis()

        // when
        requestPart(resource, 0)

        // then
        resource.lastActivityAt shouldBeGreaterThan before
        resource.lastActivityAt shouldBeGreaterThanOrEqual requestedAt
    }

    @Test
    fun `lastActivityAt should move when the Resource advertises`() {
        // given
        val resource = outgoingResource()
        resource.backdateActivityForTest(A_MINUTE)
        val advertisedAt = System.currentTimeMillis()

        // when
        resource.advertise()

        // then
        resource.status shouldBe ResourceConstants.ADVERTISED
        resource.lastActivityAt shouldBeGreaterThanOrEqual advertisedAt
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
            appName = "activitytest",
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

    /**
     * An initiator-side Resource on [link] that has not been advertised. Its data is random, so
     * compression does not shrink it, and it takes several parts.
     */
    private fun outgoingResource(): Resource {
        val data = ByteArray(2_000).also { Random(1).nextBytes(it) }
        val resource = Resource.create(data = data, link = link, advertise = false)
        resource.parts.size shouldBeGreaterThanOrEqual 2

        return resource
    }

    /** An initiator-side Resource on [link] whose advertisement has gone out. */
    private fun advertisedResource(): Resource = outgoingResource().also { it.advertise() }

    /**
     * Plays the receiver asking for part [index] of [resource]. The request is what
     * [Resource.handleRequest] parses: a flag byte, the resource hash, then the map hash of each
     * wanted part.
     */
    private fun requestPart(resource: Resource, index: Int) {
        val part = checkNotNull(resource.parts[index])
        val mapHash = Hashes.fullHash(part + resource.randomHash).copyOf(ResourceConstants.MAPHASH_LEN)

        resource.handleRequest(byteArrayOf(ResourceConstants.HASHMAP_IS_NOT_EXHAUSTED.toByte()) + resource.hash + mapHash)
    }

    private companion object {
        /** Far enough into the past that a fresh activity time is clearly after it. */
        const val A_MINUTE = 60_000L
    }
}
