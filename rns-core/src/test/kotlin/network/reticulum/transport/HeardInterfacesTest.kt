package network.reticulum.transport

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketType
import network.reticulum.common.TransportType
import network.reticulum.common.toKey
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Checks the record of the interfaces a destination was heard on, and the route listeners.
 *
 * Every test feeds real announce packets into [Transport.inbound]. So the packet filter, the
 * announce validation and the path ranking all run as they do for a packet from the network.
 */
class HeardInterfacesTest {

    @TempDir
    lateinit var storage: File

    private val wifi = HeardFakeInterface(name = "heardWifi0", hash = byteArrayOf(111))
    private val bluetooth = HeardFakeInterface(name = "heardBle0", hash = byteArrayOf(112))
    private val peer = AnnouncingPeer()
    private val listeners = mutableListOf<RouteListener>()
    private val localDestinations = mutableListOf<Destination>()

    /**
     * Starts [Transport] with its files in a temporary directory.
     *
     * [Transport] is a singleton that every test in this module shares. It keeps its cache and
     * its path table under the home directory unless it is told another place.
     */
    @BeforeEach
    fun start() {
        Transport.stop()
        Transport.pathTable.clear()
        Transport.setCachePath(File(storage, "cache").path)
        Transport.setStoragePath(File(storage, "storage").path)
        Transport.start(transportIdentity = Identity.create(), enableTransport = false)
        Transport.registerInterface(wifi)
        Transport.registerInterface(bluetooth)
    }

    @AfterEach
    fun stop() {
        listeners.forEach(Transport::removeRouteListener)
        localDestinations.forEach(Transport::deregisterDestination)
        Transport.setDestinationPinnedToFallback(destinationHash = peer.destinationHash, pinned = false)
        Transport.setFallbackInterface(interfaceName = bluetooth.name, isFallback = false)
        Transport.deregisterInterface(wifi)
        Transport.deregisterInterface(bluetooth)
        Transport.pathTable.clear()
        Transport.stop()
        Transport.setCachePath(DEFAULT_CACHE_PATH)
        Transport.setStoragePath(DEFAULT_STORAGE_PATH)
    }

    @Test
    fun `heardInterfaces should record a direct announce with one hop`() {
        // given
        val before = System.currentTimeMillis()

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // then
        val sighting = Transport.heardInterfaces(peer.destinationHash).getValue(wifi.hash.toKey())

        assertEquals(1, sighting.hops)
        assertTrue(sighting.heardAtMs in before..System.currentTimeMillis())
    }

    @Test
    fun `heardInterfaces should record a relayed announce with its hop count`() {
        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS, wireHops = 2), wifi)

        // then
        assertEquals(3, Transport.heardInterfaces(peer.destinationHash).getValue(wifi.hash.toKey()).hops)
    }

    @Test
    fun `heardInterfaces should be empty for a destination that was never heard`() {
        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should record the same destination on two interfaces`() {
        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS, wireHops = 1), wifi)
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS + 1), bluetooth)

        // then
        val heard = Transport.heardInterfaces(peer.destinationHash)

        assertEquals(setOf(wifi.hash.toKey(), bluetooth.hash.toKey()), heard.keys)
        assertEquals(2, heard.getValue(wifi.hash.toKey()).hops)
        assertEquals(1, heard.getValue(bluetooth.hash.toKey()).hops)
    }

    @Test
    fun `heardInterfaces should keep only the last announce heard on one interface`() {
        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS + 1, wireHops = 2), wifi)

        // then
        val heard = Transport.heardInterfaces(peer.destinationHash)

        assertEquals(setOf(wifi.hash.toKey()), heard.keys)
        assertEquals(3, heard.getValue(wifi.hash.toKey()).hops)
    }

    @Test
    fun `heardInterfaces should record an announce that loses the path ranking`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // when
        // This announce is older and further away, so the path table keeps the first one.
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS - 10, wireHops = 3), bluetooth)

        // then
        assertArrayEquals(wifi.hash, Transport.pathInterfaceHash(peer.destinationHash))
        assertEquals(1, Transport.hopsTo(peer.destinationHash))
        assertEquals(4, Transport.heardInterfaces(peer.destinationHash).getValue(bluetooth.hash.toKey()).hops)
    }

    @Test
    fun `heardInterfaces should record the second arrival of one announce on another interface`() {
        // given
        val announce = peer.announce(emittedAt = NOW_SECONDS)
        val packetHash = checkNotNull(Packet.unpack(announce)).packetHash

        Transport.inbound(announce, wifi)

        assertTrue(
            Transport.packetHashlistContainsForTest(packetHash),
            "the packet filter must know the announce before its second arrival",
        )

        // when
        Transport.inbound(announce, bluetooth)

        // then
        assertArrayEquals(wifi.hash, Transport.pathInterfaceHash(peer.destinationHash))
        assertEquals(1, Transport.heardInterfaces(peer.destinationHash).getValue(bluetooth.hash.toKey()).hops)
    }

    @Test
    fun `heardInterfaces should not record an announce with a wrong signature`() {
        // given
        val announce = peer.announce(emittedAt = NOW_SECONDS)

        announce[announce.lastIndex] = (announce.last() + 1).toByte()

        // when
        Transport.inbound(announce, wifi)

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
        assertFalse(Transport.hasPath(peer.destinationHash))
    }

    @Test
    fun `heardInterfaces should not record an announce that a pin rejected`() {
        // given
        Transport.setDestinationPinnedToFallback(destinationHash = peer.destinationHash, pinned = true)

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should record a pinned destination on its fallback interface`() {
        // given
        Transport.setFallbackInterface(interfaceName = bluetooth.name, isFallback = true)
        Transport.setDestinationPinnedToFallback(destinationHash = peer.destinationHash, pinned = true)

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), bluetooth)

        // then
        assertEquals(setOf(bluetooth.hash.toKey()), Transport.heardInterfaces(peer.destinationHash).keys)
    }

    @Test
    fun `heardInterfaces should not record an announce for a destination of this node`() {
        // given
        val local = Destination.create(
            identity = Identity.create(),
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "heardtest",
            aspects = arrayOf("local"),
        )
        val announce = checkNotNull(local.announce(send = false)).pack()

        localDestinations += local

        // when
        Transport.inbound(announce, wifi)

        // then
        assertTrue(Transport.heardInterfaces(local.hash).isEmpty())
    }

    @Test
    fun `heardInterfaces should forget an announce over the hop limit at the next cull`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS, wireHops = TransportConstants.PATHFINDER_M), wifi)

        assertFalse(Transport.hasPath(peer.destinationHash))
        assertEquals(setOf(wifi.hash.toKey()), Transport.heardInterfaces(peer.destinationHash).keys)

        // when
        Transport.cullTablesNow()

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should return a copy that later announces do not change`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        val heard = Transport.heardInterfaces(peer.destinationHash)

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS + 1), bluetooth)

        // then
        assertEquals(setOf(wifi.hash.toKey()), heard.keys)
    }

    @Test
    fun `heardInterfaces should be empty after expirePath dropped the path`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // when
        Transport.expirePath(peer.destinationHash)

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should be empty after expirePathOn dropped the path`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // when
        Transport.expirePathOn(destinationHash = peer.destinationHash, interfaceHash = wifi.hash)

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should be empty after expireDeadPaths dropped the path`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        wifi.online = false

        // when
        Transport.expireDeadPaths()

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should be empty after removeBlackholedPaths dropped the path`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.blackholeIdentity(peer.identityHash)

        // when
        Transport.removeBlackholedPaths()

        // then
        assertFalse(Transport.hasPath(peer.destinationHash))
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should be empty after the path expired`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.setPathExpiresForTest(destHash = peer.destinationHash, expiresMs = System.currentTimeMillis() - 1)

        // when
        Transport.cullTablesNow()

        // then
        assertFalse(Transport.hasPath(peer.destinationHash))
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should be empty after the path was taken out of the path table`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.pathTable.remove(peer.destinationHash.toKey())

        // when
        Transport.cullTablesNow()

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `heardInterfaces should keep a destination that has a path through a cull`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // when
        Transport.cullTablesNow()

        // then
        assertEquals(setOf(wifi.hash.toKey()), Transport.heardInterfaces(peer.destinationHash).keys)
    }

    @Test
    fun `heardInterfaces should be empty after stop cleared the path table`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)

        // when
        Transport.stop()

        // then
        assertTrue(Transport.heardInterfaces(peer.destinationHash).isEmpty())
    }

    @Test
    fun `addRouteListener should call the listener with the destination that was heard`() {
        // given
        val heard = recordingListener()

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()

        // then
        assertTrue(heard.isNotEmpty())
        assertTrue(heard.all { call -> call.destinationHash.contentEquals(peer.destinationHash) })
    }

    @Test
    fun `addRouteListener should call the listener after the path is stored`() {
        // given
        val heard = recordingListener()

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()

        // then
        assertArrayEquals(wifi.hash, heard.last().pathInterfaceHash)
        assertEquals(setOf(wifi.hash.toKey()), heard.last().heardOn)
    }

    @Test
    fun `addRouteListener should call the listener for an announce that loses the path ranking`() {
        // given
        val announce = peer.announce(emittedAt = NOW_SECONDS)

        Transport.inbound(announce, wifi)
        Transport.drainRouteListenersForTest()

        val heard = recordingListener()

        // when
        Transport.inbound(announce, bluetooth)
        Transport.drainRouteListenersForTest()

        // then
        assertEquals(1, heard.size)
        assertEquals(setOf(wifi.hash.toKey(), bluetooth.hash.toKey()), heard.single().heardOn)
    }

    @Test
    fun `addRouteListener should call the listener after the path is dropped`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()

        val heard = recordingListener()

        // when
        Transport.expirePath(peer.destinationHash)
        Transport.drainRouteListenersForTest()

        // then
        assertEquals(1, heard.size)
        assertNull(heard.single().pathInterfaceHash)
        assertTrue(heard.single().heardOn.isEmpty())
    }

    @Test
    fun `addRouteListener should call the listener after the path expired`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.setPathExpiresForTest(destHash = peer.destinationHash, expiresMs = System.currentTimeMillis() - 1)
        Transport.drainRouteListenersForTest()

        val heard = recordingListener()

        // when
        Transport.cullTablesNow()
        Transport.drainRouteListenersForTest()

        // then
        assertEquals(1, heard.size)
        assertNull(heard.single().pathInterfaceHash)
    }

    @Test
    fun `addRouteListener should call the listener when a cull forgets a destination with no path`() {
        // given
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.pathTable.remove(peer.destinationHash.toKey())
        Transport.drainRouteListenersForTest()

        val heard = recordingListener()

        // when
        Transport.cullTablesNow()
        Transport.drainRouteListenersForTest()

        // then
        assertEquals(1, heard.size)
        assertTrue(heard.single().heardOn.isEmpty())
    }

    @Test
    fun `addRouteListener should call the listener again for a change that came during a call`() {
        // given
        val firstCallStarted = CountDownLatch(1)
        val firstCallMayFinish = CountDownLatch(1)
        val heardOn = CopyOnWriteArrayList<Set<ByteArrayKey>>()

        listen { destinationHash ->
            firstCallStarted.countDown()
            firstCallMayFinish.await()
            heardOn += Transport.heardInterfaces(destinationHash).keys
        }

        try {
            Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
            firstCallStarted.await()

            // when
            Transport.inbound(peer.announce(emittedAt = NOW_SECONDS + 1), bluetooth)
        } finally {
            firstCallMayFinish.countDown()
        }

        Transport.drainRouteListenersForTest()

        // then
        assertEquals(2, heardOn.size)
        assertEquals(setOf(wifi.hash.toKey(), bluetooth.hash.toKey()), heardOn.last())
    }

    @Test
    fun `addRouteListener should not call the listener for an announce with a wrong signature`() {
        // given
        val heard = recordingListener()
        val announce = peer.announce(emittedAt = NOW_SECONDS)

        announce[announce.lastIndex] = (announce.last() + 1).toByte()

        // when
        Transport.inbound(announce, wifi)
        Transport.drainRouteListenersForTest()

        // then
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `addRouteListener should keep the listener through a stop and a start`() {
        // given
        val heard = recordingListener()

        Transport.stop()
        Transport.start(transportIdentity = Identity.create(), enableTransport = false)

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()

        // then
        assertTrue(heard.isNotEmpty())
    }

    @Test
    fun `addRouteListener should call the other listeners when one throws`() {
        // given
        listen { throw IllegalStateException("a listener that fails") }

        val heard = recordingListener()

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()

        // then
        assertTrue(heard.isNotEmpty())
    }

    @Test
    fun `addRouteListener should not stop announce handling when a listener throws`() {
        // given
        listen { throw IllegalStateException("a listener that fails") }

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS + 1), bluetooth)

        // then
        // The second announce is newer, so it takes the path.
        assertArrayEquals(bluetooth.hash, Transport.pathInterfaceHash(peer.destinationHash))
        assertEquals(2, Transport.heardInterfaces(peer.destinationHash).size)
    }

    @Test
    fun `addRouteListener should not hold up announce handling while a listener runs`() {
        // given
        val listenerStarted = CountDownLatch(1)
        val listenerMayFinish = CountDownLatch(1)

        listen { _ ->
            listenerStarted.countDown()
            listenerMayFinish.await()
        }

        try {
            // when
            // A listener called on the inbound thread, or under the transport's lock, would never
            // return here. The timeout turns that into a failure.
            assertTimeoutPreemptively(LISTENER_TIMEOUT) {
                Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
                listenerStarted.await()
                Transport.inbound(peer.announce(emittedAt = NOW_SECONDS + 1), bluetooth)
            }

            // then
            assertEquals(2, Transport.heardInterfaces(peer.destinationHash).size)
        } finally {
            listenerMayFinish.countDown()
        }
    }

    @Test
    fun `removeRouteListener should stop the calls to the listener`() {
        // given
        val heard = recordingListener()

        Transport.removeRouteListener(listeners.single())

        // when
        Transport.inbound(peer.announce(emittedAt = NOW_SECONDS), wifi)
        Transport.drainRouteListenersForTest()

        // then
        assertTrue(heard.isEmpty())
        assertNotNull(Transport.pathInterfaceHash(peer.destinationHash))
    }

    /**
     * Registers a listener, and remembers it so the test's teardown removes it.
     *
     * @param listener the listener to register.
     */
    private fun listen(listener: RouteListener) {
        listeners += listener
        Transport.addRouteListener(listener)
    }

    /**
     * Registers a listener that notes what [Transport] knows at each call.
     *
     * @return the calls so far, oldest first. The list grows as the listener is called.
     */
    private fun recordingListener(): List<ListenerCall> {
        val calls = CopyOnWriteArrayList<ListenerCall>()

        listen { destinationHash ->
            calls += ListenerCall(
                destinationHash = destinationHash,
                pathInterfaceHash = Transport.pathInterfaceHash(destinationHash),
                heardOn = Transport.heardInterfaces(destinationHash).keys,
            )
        }

        return calls
    }
}

/**
 * What a route listener was given, and what [Transport] knew during that call.
 *
 * @param destinationHash the hash the listener was called with.
 * @param pathInterfaceHash the interface of the destination's path, or null when it had no path.
 * @param heardOn the interfaces the destination was heard on.
 */
private class ListenerCall(
    val destinationHash: ByteArray,
    val pathInterfaceHash: ByteArray?,
    val heardOn: Set<ByteArrayKey>,
)

/**
 * A destination on another node, and the announce packets it would send.
 *
 * It is not registered with [Transport]. [Transport] skips announces for its own destinations.
 */
private class AnnouncingPeer {

    private val identity = Identity.create()
    private val nameHash = Destination.computeNameHash("heardtest", listOf("peer"))

    val destinationHash: ByteArray = Destination.computeHash(nameHash, identity.hash)
    val identityHash: ByteArray = identity.hash

    /**
     * Builds one signed announce packet, as it arrives from an interface.
     *
     * @param emittedAt the time the announce says it was sent, in epoch seconds. The path table
     *   ranks announces by it.
     * @param wireHops the hop count in the packet. 0 is an announce straight from the destination.
     * @return the packet's bytes.
     */
    fun announce(emittedAt: Long, wireHops: Int = 0): ByteArray {
        val emittedAtBytes = ByteArray(EMISSION_TIME_BYTES) { index ->
            (emittedAt shr (8 * (EMISSION_TIME_BYTES - 1 - index))).toByte()
        }
        val randomHash = Hashes.getRandomHash().copyOf(RANDOM_BYTES) + emittedAtBytes
        val publicKey = identity.getPublicKey()
        val signature = identity.sign(destinationHash + publicKey + nameHash + randomHash)
        val packet = Packet.createRaw(
            destinationHash = destinationHash,
            data = publicKey + nameHash + randomHash + signature,
            packetType = PacketType.ANNOUNCE,
            destinationType = DestinationType.SINGLE,
            transportType = TransportType.BROADCAST,
        )
        val raw = packet.pack()

        raw[HOPS_INDEX] = wireHops.toByte()

        return raw
    }
}

/**
 * An interface that receives what a test feeds in and sends nothing.
 *
 * @param name the interface's name.
 * @param hash the hash [Transport] files the interface's paths and sightings under.
 */
private class HeardFakeInterface(
    override val name: String,
    override val hash: ByteArray,
) : InterfaceRef {
    override val canSend: Boolean = true
    override val canReceive: Boolean = true
    override val mode: InterfaceMode = InterfaceMode.FULL
    override var online: Boolean = true
    override var tunnelId: ByteArray? = null
    override var wantsTunnel: Boolean = false

    override fun send(data: ByteArray) = Unit
}

/** The time the test announces are sent at, in epoch seconds. The tests add to it for a newer announce. */
private val NOW_SECONDS = System.currentTimeMillis() / 1000

/** How long a test waits for announce handling that a listener must not hold up. */
private val LISTENER_TIMEOUT = Duration.ofSeconds(10)

/** Where [Transport] keeps its cache when no test has moved it. */
private val DEFAULT_CACHE_PATH = System.getProperty("user.home") + "/.reticulum/cache"

/** Where [Transport] keeps its path table when no test has moved it. */
private val DEFAULT_STORAGE_PATH = System.getProperty("user.home") + "/.reticulum/storage"

/** How many bytes of an announce's random hash hold random data. The rest hold the emission time. */
private const val RANDOM_BYTES = 5

/** How many bytes of an announce's random hash hold the emission time. */
private const val EMISSION_TIME_BYTES = 5

/** Index of the hop count in a packed packet. The flags byte comes before it. */
private const val HOPS_INDEX = 1
