package network.reticulum.resource

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
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
import org.msgpack.core.MessagePack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Pins what a sender-side Resource does with copies of one part request.
 *
 * A receiver asks for the next hashmap segment with a request whose first byte is
 * [ResourceConstants.HASHMAP_IS_EXHAUSTED]. The sender counts parts from its receiver height
 * to find the segment. The request at the second boundary, part [SECOND_BOUNDARY], is the
 * first that moves the height, from 0 to 72. Two copies of it running at once used to mix the
 * old start with the new base, land on part 220, and cancel the transfer with
 * "Resource sequencing error". Python never meets this. One thread handles every packet of an
 * interface, and `Link.receive` drops a request packet it has seen before.
 *
 * One receiving link stands in for both ends, as in [ResourceActivityTest]. The receiver's
 * requests are played by hand. [CapturingInterface] can hold one thread inside its first send,
 * so a test chooses which copy runs first.
 */
@DisplayName("Copies of one resource request")
class ResourceRequestCopiesTest {

    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = CopyOnWriteArrayList<ByteArray>()

        /** The thread whose sends wait for [release]. Null holds nobody. */
        @Volatile
        var heldThread: Thread? = null

        /** Counts down once [heldThread] is waiting inside a send. */
        val arrived = CountDownLatch(1)

        /** Lets [heldThread] finish its sends. */
        val release = CountDownLatch(1)

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAC.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            if (Thread.currentThread() === heldThread) {
                arrived.countDown()
                release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }

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

        iface = CapturingInterface(name = "copies-${System.nanoTime()}")
        Transport.registerInterface(iface)

        link = activeReceivingLink()
    }

    @AfterEach
    fun teardown() {
        iface.release.countDown()
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
    fun `handleRequest should not cancel the transfer when copies of a hashmap request run at once`() {
        // given
        val resource = advertisedResource()
        val request = hashmapRequest(resource)
        val first = thread(start = false) { resource.handleRequest(request) }
        val second = thread(start = false) { resource.handleRequest(request) }
        iface.heldThread = first

        // when
        // The first copy stops inside its first send, after it has read the height. The
        // second copy runs through. Then the first copy goes on.
        first.start()
        iface.arrived.await(WAIT_MILLIS, TimeUnit.MILLISECONDS) shouldBe true
        second.start()
        second.join(WAIT_MILLIS)
        iface.release.countDown()
        first.join(WAIT_MILLIS)

        // then
        resource.status shouldBe ResourceConstants.TRANSFERRING
        resource.receiverMinConsecutiveHeightForTest() shouldBe HEIGHT_AFTER_SECOND_BOUNDARY
        hashmapSegmentsSent() shouldBe listOf(2, 2)
    }

    @Test
    fun `handleRequest should serve copies of a hashmap request that arrive one after another`() {
        // given
        val resource = advertisedResource()
        val request = hashmapRequest(resource)

        // when
        resource.handleRequest(request)
        resource.handleRequest(request)

        // then
        resource.status shouldBe ResourceConstants.TRANSFERRING
        resource.receiverMinConsecutiveHeightForTest() shouldBe HEIGHT_AFTER_SECOND_BOUNDARY
        hashmapSegmentsSent() shouldBe listOf(2, 2)
    }

    @Test
    fun `receive should drop a copy of a request packet it has already seen`() {
        // given
        val resource = advertisedResource()
        val raw = requestPacket(partRequest(resource, 0))

        // when
        deliver(raw)
        deliver(raw)

        // then
        partsSent() shouldBe 1
    }

    @Test
    fun `receive should serve a request sent again as a new packet`() {
        // given
        val resource = advertisedResource()
        val plaintext = partRequest(resource, 0)

        // when
        deliver(requestPacket(plaintext))
        deliver(requestPacket(plaintext))

        // then
        partsSent() shouldBe 2
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
            appName = "copiestest",
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
     * An initiator-side Resource on [link] whose advertisement has gone out. Its data is
     * random, so compression does not shrink it, and it has more parts than two hashmap
     * segments hold. The advertisement is dropped from [CapturingInterface.sent].
     */
    private fun advertisedResource(): Resource {
        val data = ByteArray(DATA_SIZE).also { Random(1).nextBytes(it) }
        val resource = Resource.create(data = data, link = link, advertise = false)
        resource.parts.size shouldBeGreaterThan SECOND_BOUNDARY
        resource.advertise()
        iface.sent.clear()

        return resource
    }

    /**
     * The request a receiver sends at [SECOND_BOUNDARY]. It holds every part before the boundary
     * except the last two. Its hashmap ends at the boundary. So it asks for those two parts and
     * for the next hashmap segment. The last map hash it knows is the one of the part before the
     * boundary.
     */
    private fun hashmapRequest(resource: Resource): ByteArray =
        byteArrayOf(ResourceConstants.HASHMAP_IS_EXHAUSTED.toByte()) +
            mapHash(resource, SECOND_BOUNDARY - 1) +
            resource.hash +
            mapHash(resource, SECOND_BOUNDARY - 2) +
            mapHash(resource, SECOND_BOUNDARY - 1)

    /** A request for part [index] alone, with the hashmap not exhausted. */
    private fun partRequest(resource: Resource, index: Int): ByteArray =
        byteArrayOf(ResourceConstants.HASHMAP_IS_NOT_EXHAUSTED.toByte()) + resource.hash + mapHash(resource, index)

    /** The map hash of part [index], as a receiver would send it back. */
    private fun mapHash(resource: Resource, index: Int): ByteArray {
        val part = checkNotNull(resource.parts[index])

        return Hashes.fullHash(part + resource.randomHash).copyOf(ResourceConstants.MAPHASH_LEN)
    }

    /** The raw bytes of a RESOURCE_REQ packet carrying [plaintext] to [link]. */
    private fun requestPacket(plaintext: ByteArray): ByteArray {
        val packet = Packet.createRaw(
            destinationHash = link.linkId,
            data = link.encrypt(plaintext),
            packetType = PacketType.DATA,
            destinationType = DestinationType.LINK,
            context = PacketContext.RESOURCE_REQ,
            mtu = link.mtu,
        )
        packet.pack()

        return checkNotNull(packet.raw)
    }

    /** Plays [raw] arriving at [link] over [iface], as Transport would hand it over. */
    private fun deliver(raw: ByteArray) {
        val packet = checkNotNull(Packet.unpack(raw))
        packet.receivingInterfaceHash = iface.hash

        link.receive(packet)
    }

    /** Every packet [iface] captured, unpacked, with the given [context]. */
    private fun sentWithContext(context: PacketContext): List<Packet> =
        iface.sent.mapNotNull { Packet.unpack(it) }.filter { it.context == context }

    /** How many parts went out. */
    private fun partsSent(): Int = sentWithContext(PacketContext.RESOURCE).size

    /** The segment number of each hashmap update that went out, in order. */
    private fun hashmapSegmentsSent(): List<Int> = sentWithContext(PacketContext.RESOURCE_HMU).map { packet ->
        val plaintext = checkNotNull(link.decrypt(packet.data))
        val update = plaintext.copyOfRange(ResourceConstants.RESOURCE_HASH_LEN, plaintext.size)
        val unpacker = MessagePack.newDefaultUnpacker(update)
        unpacker.unpackArrayHeader()
        unpacker.unpackInt().also { unpacker.close() }
    }

    private companion object {
        /** Enough random bytes for more than two hashmap segments of parts. */
        const val DATA_SIZE = 75_000

        /** The first part of the third hashmap segment: 148 with the default MDU. */
        val SECOND_BOUNDARY = 2 * ResourceAdvertisement.HASHMAP_MAX_LEN

        /**
         * The sender's receiver height after it serves the request at [SECOND_BOUNDARY]:
         * python's `max(part_index - 1 - WINDOW_MAX, 0)`, which is 72.
         */
        val HEIGHT_AFTER_SECOND_BOUNDARY = SECOND_BOUNDARY - 1 - ResourceConstants.WINDOW_MAX

        /** Longer than any step here takes, short enough that a hang fails the test. */
        const val WAIT_MILLIS = 10_000L
    }
}
