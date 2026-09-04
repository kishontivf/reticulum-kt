package network.reticulum.transport

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which arriving announces may be held when an interface is over its announce allocation.
 *
 * The path-response exemption is the reason this is tested rather than read: holding a solicited
 * response is indistinguishable, from the requester's side, from nobody knowing the route — it
 * waits out its budget and sends unrouted. Measured between two phones on a busy relay interface,
 * where both copies of the response were held and the waiting message was lost.
 */
@DisplayName("AnnounceFilter.shouldHold")
class AnnounceIngressTest {

    @Test
    fun `holds an unsolicited announce for an unknown destination when over allocation`() {
        AnnounceFilter.shouldHold(
            isKnownDestination = false,
            isPathResponse = false,
            isOverAllocation = true
        ) shouldBe true
    }

    @Test
    fun `never holds a path response, however busy the interface`() {
        AnnounceFilter.shouldHold(
            isKnownDestination = false,
            isPathResponse = true,
            isOverAllocation = true
        ) shouldBe false
    }

    @Test
    fun `never holds an announce for a destination already known`() {
        AnnounceFilter.shouldHold(
            isKnownDestination = true,
            isPathResponse = false,
            isOverAllocation = true
        ) shouldBe false
    }

    @Test
    fun `holds nothing while the interface is within its allocation`() {
        AnnounceFilter.shouldHold(
            isKnownDestination = false,
            isPathResponse = false,
            isOverAllocation = false
        ) shouldBe false
    }
}
