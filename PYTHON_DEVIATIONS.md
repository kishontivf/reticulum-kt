# Python Reference Implementation Deviations

This document tracks intentional behavioral differences between the
Kotlin (reticulum-kt) implementation and the Python reference (RNS).
Each entry explains **what** differs, **why**, and the **risk**.

Python is always the ground truth. Deviations documented here are
temporary workarounds or conscious trade-offs, not design choices.

---

## Transport: Stale Path Entry Removal on Outbound

**Python behavior** (`Transport.py:load_path_table`):
When loading persisted paths on startup, Python resolves the stored
interface hash to a live interface object. If `find_interface_from_hash()`
returns `None`, the path entry is simply not loaded (line 105:
"The interface is no longer available").

**Kotlin behavior** (`Transport.kt:processOutbound`):
Kotlin loads all persisted paths regardless of interface availability.
During outbound routing, if `findInterfaceByHash()` returns null for
a path entry, Kotlin removes the stale entry from the path table at
that point and falls through to broadcast.

**Why**: Kotlin's path persistence loads paths before all interfaces
are registered (AutoInterface/discovered interfaces register later).
Filtering at load time would discard valid paths for interfaces that
haven't connected yet.

**Risk**: Low. The end result is the same — stale paths are eventually
removed. The timing differs (Python: at load; Kotlin: at first use).

**Resolution**: Add a deferred path validation pass after all interfaces
are registered, matching Python's load-time filtering.

---

## LXMRouter: Ratchet Self-Storage Removed from generateAnnounceData

**Python behavior** (`Destination.py:announce()`):
Python does NOT store the ratchet public key under the local
destination's hash during announce generation. The ratchet public
key is only stored by REMOTE nodes that receive the announce.

**Kotlin behavior (before fix)**:
`generateAnnounceData()` called `setRatchetForDestination(hash, ratchetPub)`
and `Identity.rememberRatchet(hash, ratchetPub)`, storing the ratchet
public key under the local destination hash. This confused the
`Destination.encrypt()` path because `Identity.get_ratchet(destHash)`
would find a public key for a LOCAL destination and use it instead
of the identity's base key.

**Kotlin behavior (after fix)**: Matches Python — removed self-storage.

**Risk**: None. This was a bug fix, not a deviation.

---

## AutoInterface: Adaptive Announce Interval

**Python behavior** (`AutoInterface.py:announce_handler`):
Sends multicast discovery announcements every 1.6 seconds continuously
with no adaptation. This interval is hardcoded and never changes.

**Kotlin behavior** (`AutoInterface.kt:startAnnouncementLoop`):
The announce interval adapts based on peer activity and device state:

| State | Announce Interval | vs Python (1.6s) |
|-------|------------------|------------------|
| Startup / peer change | 1.6s (+ immediate reply) | Same |
| Stable, 30s | ~60s | 37x less |
| Stable, 60s+ | 2 minutes | 75x less |
| Doze, stable | 10 minutes | 375x less |
| New peer during Doze | Immediate reply → 1.6s | Same responsiveness |

The interval linearly ramps from 1.6s to `maxAnnounceIntervalMs *
throttleMultiplier` over 60 seconds after the last peer topology
change. Any peer add/remove resets to fast mode AND triggers an
immediate announce so the new peer discovers us within ~1 second.

Doze awareness: `NativeReticulumProtocol` observes `DozeStateObserver`
and sets `AutoInterface.throttleMultiplier` to 5.0 during Doze,
scaling the max interval from 2 minutes to 10 minutes.

**Why**: Continuous 1.6s multicast is the single biggest battery drain
on the native stack. Receiving other nodes' multicast is free (blocking
`socket.receive()`), but sending wakes the WiFi radio every 1.6s
indefinitely. Since peer discovery is symmetric, other nodes sending
at their rate still discover us via receiving — we only need to send
often enough that they discover us within a reasonable time.

**Multicast socket cleanup**: Kotlin explicitly calls `leaveGroup()`
before closing multicast sockets on detach, preventing OS-level
multicast group membership from lingering after the interface is
stopped. Python relies on `socket.close()` for implicit cleanup.

**Risk**: Low. A node running Kotlin at 2-minute intervals next to a
node running Python at 1.6s will still discover each other: the Python
node's multicast arrives within 1.6s, and the Kotlin node's immediate
reply fires within ~1 second. After discovery, unicast data flow is
unaffected.

**Resolution**: This is an intentional improvement, not a workaround.
`throttleMultiplier` can be set to 1.0 and `maxAnnounceIntervalMs`
can be set to `ANNOUNCE_INTERVAL_MS` to match exact Python behavior
if needed.

---

## Transport: Interfaces a Destination Was Heard On, with Hop Counts

**Python behavior** (`Transport.py`, announce handling in `inbound`):
Python keeps one path for each destination in `path_table`. An announce
that loses the path ranking is dropped, and nothing remembers the
interface it arrived on or its hop count. Python has no listener for a
path change. An announce handler is called only for an announce that
was added to the path table.

**Kotlin behavior** (`Transport.kt:processAnnounce`, `heardInterfaces`,
`addRouteListener`, `removeRouteListener`):
`Transport` also keeps, for each destination, the last announce heard on
each interface. Each record is an `InterfaceSighting`: the arrival time
in epoch milliseconds, and the hop count as `PathEntry.hops` holds it.
A destination that announced to this node directly has 1. One behind a
local shared instance has 0. The interface is keyed by its hash, the
value `PathEntry.receivingInterfaceHash` holds.

- `Transport.heardInterfaces(destinationHash)` returns a copy of the
  records for one destination.
- An announce is recorded after it passed validation, signature
  included. It is recorded before the path ranking, so an announce with
  a known random blob, an older emission time, a worse hop count or more
  hops than `PATHFINDER_M` is still recorded. A path response is
  recorded like any other announce.
- The packet filter lets a second copy of an announce through, as
  Python's does. So the same announce on a second interface is recorded
  too.
- Three kinds of valid announce are not recorded: one held by ingress
  limiting until it is released, one for this node's own destination,
  and one that a pin rejected (`setDestinationPinnedToFallback`).
- The records of a destination are dropped when its path is dropped or
  expires, and when `Transport` stops. A record has no age limit of its
  own. The records are kept in memory only.
- `Transport.addRouteListener(listener)` registers a `RouteListener`.
  It is called with the destination hash after a record or a path was
  stored, and after a path was dropped. It is not called when
  `Transport` stops. The call runs on a thread of its own, with none of
  `Transport`'s locks held. A listener that throws does not stop
  announce handling.

**Why**: The app shows the user every link a contact can be reached on,
and whether each one is direct. The path table keeps only the winner of
the ranking, and the hop count of a losing announce cannot be worked out
later. reticulum-swift has the same addition, so both apps show the same
list. See its `port-deviations.md`, the entry
"`PathTable.lastHeardByInterface` carries hop count +
`heardInterfaces(for:)` reader".

**Risk**: Low. The path ranking, the announce rebroadcast and the data
saved to disk are unchanged. The cost is one small map entry for each
announce, and one more thread while `Transport` runs. After each cull
the map is no larger than the path table.

**Resolution**: This is an intentional addition, not a workaround. The
full entry is in `port-deviations.md`. Released as 0.3.0.

---

## Conformance Test Status

78 of 79 conformance tests pass (only `bz2_compress` differs due to
library version producing different but equally valid compressed output).

All 4 ratchet lifecycle tests pass, including:
- Announce with ratchet pack/unpack
- Ratchet extraction from announce
- Full lifecycle encrypt/decrypt
- Cross-implementation encrypt/decrypt
