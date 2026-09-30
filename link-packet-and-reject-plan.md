# Plan: a link packet lost between connections, and a rejection that is never sent

Written on 2026-09-30, after an iPhone's automatic reply reached a Pixel 8 Pro six and a half
minutes late. The decisions were taken by the developer the same day, and the plan was built the
same day; see "As built" at the end.

Line numbers are at this fork's tag `0.1.0`, which is what the Analog app ships, and at Python
Reticulum's `master`, commit `7f2b3b9` (read on 2026-09-30).

## What goes wrong

1. **A link packet that first arrives over another connection is lost.** Our phones send the same
   packet over several connections at once (WebRTC, Wi-Fi, Bluetooth). Transport records a packet's
   hash as seen before delivering it (`Transport.kt:3195–3205`, as Python's `Transport.py:1944–1961`
   does). A packet for a local link that arrives on another interface than the link's own is then
   skipped (`Transport.kt:4524–4529`, "Link interface mismatch"). Python makes the same check
   (`Transport.py:2574`), but it then takes the packet's hash off both filter lists "so the link can
   receive the packet when it finally arrives over another path" (`Transport.py:2586–2594`). The
   port leaves that step out, so the copy that arrives on the link's own connection is discarded as
   a duplicate.

   The packet lost this way was the RTT packet of a link an iPhone had just opened. A receiving
   link becomes active, and runs its "link established" callback, only on that packet
   (`Link.kt:2135–2202`; Python's `Link.py:516–533`). LXMF-kt sets a delivery link to accept
   Resources in that callback, so the link stayed at `ACCEPT_NONE`, and the iPhone's reply was
   rejected.
2. **A rejection is never sent.** `Resource.reject` addresses the `RESOURCE_RCL` packet to the
   advertisement's 32-byte hash instead of to the link, and throws "Destination hash must be 16
   bytes" (`Resource.kt:202–213`). Python sends it over the link, with the Resource's hash as the
   data (`Resource.py:156–166`). The sender is never told. The iPhone, which has no timeout of its
   own for an unanswered offer, waited six and a half minutes before trying again.
3. **A receiver that cancels an incoming Resource does not tell the sender.** Found while reading
   Python for 2. Python's `cancel` sends `RESOURCE_RCL` over the link when the receiver cancels
   (`Resource.py:1110–1115`). The port's `cancel` sends `RESOURCE_ICL` for a sender only
   (`Resource.kt:~1472–1495`), and nothing for a receiver.

## Evidence

- **Pixel 8 Pro, 2026-09-30.** At 10:08:01.235 it accepted the link request of the iPhone's new
  link `23655b8f…` over Wi-Fi. The next packet, 64 bytes, arrived over WebRTC 25 ms before its
  Wi-Fi copy and was skipped as a mismatch; its Wi-Fi and Bluetooth copies were then "FILTERED".
  At 10:08:01.995 the reply's `RESOURCE_ADV` arrived over Wi-Fi and was rejected ("strategy:
  ACCEPT_NONE"), and the rejection failed with "Destination hash must be 16 bytes".
- **The iPhone's unified log.** The unanswered Resource ended only at 10:14:30 ("Outbound resource
  … concluded in non-complete state; marking message for retry"). The iPhone resent over a fresh
  link at 10:14:46, the Pixel accepted it, and the delivery proof came at 10:14:49.

## Decisions

Taken by the developer on 2026-09-30.

| Decision | Chosen |
|---|---|
| A link packet that arrives over another connection than its link's | Not counted as seen: still skipped, as Python does, but its hash comes off both filter lists, so the copy on the link's own connection gets through. This is Python's own rule (`Transport.py:2586–2594`), so `port-deviations.md` needs no entry |
| Where this plan lives | Here, beside `port-deviations.md`, as for LXMF-kt's plan |

## Design

### 1. A skipped link packet leaves the filter lists

In Transport's delivery to a local link, when the packet's interface is not the link's, take its
hash off the packet hash list and the previous generation of it, as Python does, and keep skipping
the packet. Keep the log line. Nothing else in the check changes.

### 2. A rejection goes over the link

Build `Resource.reject`'s `RESOURCE_RCL` the way `Resource.cancel` builds `RESOURCE_ICL`: addressed
to the link's id as a link packet, with the Resource's hash encrypted for the link as the data, and
send it. Check that the sender's side (`Link.kt`'s `processResourceRcl`) finds the Resource by that
hash and ends it as rejected, as Python's `Link.receive` does.

### 3. A receiver's cancel tells the sender

In `Resource.cancel`, when the receiver cancels and the link is active, send `RESOURCE_RCL` with
the Resource's hash, as Python does. A sender's cancel keeps sending `RESOURCE_ICL`.

### Not changed

- The interface check itself: a packet on the wrong interface is still skipped.
- A receiving link still becomes active on the RTT packet, as in Python.
- LXMF-kt and intercom-kt.

## Tests

In this repository's own style, and against the Python bridge where it can check the behaviour:

- a link packet skipped for arriving on another interface does not stay in either filter list, and
  its copy on the link's interface is delivered;
- a packet on the link's interface, and a true duplicate on the same interface, behave as before;
- `reject` sends a `RESOURCE_RCL` over the link carrying the Resource's hash, and a sender that
  receives it ends the Resource as rejected;
- a receiver's `cancel` sends `RESOURCE_RCL`, and a sender's `cancel` still sends `RESOURCE_ICL`.

## Release

To try it on phones:

1. `./gradlew publishToMavenLocal` here, which publishes `0.2.0-SNAPSHOT`.
2. In intercom-kt, set `reticulum = "0.2.0-SNAPSHOT"` and publish it to `mavenLocal`. Its
   `settings.gradle.kts` already admits this fork's snapshots from `mavenLocal`, and so does
   Analog's. Gradle takes the highest version asked for, so the `0.1.0` that LXMF-kt's snapshot
   names gives way to `0.2.0-SNAPSHOT`, and LXMF-kt needs no change for the test.
3. Build Analog, which already uses intercom-kt's `1.3.0-SNAPSHOT`.

To release: tag this fork, point LXMF-kt's `rns-core` dependency at the tag and release LXMF-kt,
then release intercom-kt with both, and bump Analog.

## Checked on phones

Each run needs the developer's go-ahead at that moment.

- An iPhone replies with an image to an Android phone over a new link, while WebRTC and Wi-Fi are
  both up: the reply arrives within seconds.
- A rejected Resource: the sender logs the rejection at once instead of timing out.

## Open points

- At 10:08:17 the Pixel also received a 64-byte packet over WebRTC for a link it does not know
  (`5103b26c…`). Nothing here explains it, and nothing in this plan changes it.

## As built

Built on 2026-09-30 and published to `mavenLocal` as `0.2.0-SNAPSHOT`, uncommitted.

- **Part 1** is in `Transport.kt`'s local link delivery: the mismatch branch keeps its log line and
  its skip, and takes the packet's hash off `packetHashlist` and `packetHashlistPrev` first, as
  `Transport.py:2586–2594` does.
- **Parts 2 and 3** are in `Resource.kt`. `reject` builds its `RESOURCE_RCL` as `cancel` builds its
  `RESOURCE_ICL`: to the link's id, as a link packet, with the Resource's hash encrypted for the
  link. `cancel` sends on an active link in both roles: `RESOURCE_ICL` from the sender,
  `RESOURCE_RCL` from the receiver.
- **Tests.** `LinkPacketInterfaceMismatchTest` (3) and `ResourceRejectAndCancelTest` (4) in
  `rns-core`; 153 tests there, none failed. LXMF-kt's 95 and intercom-kt's 193 pass against the
  snapshot.

Not like Python yet, and left for a decision: the sender's `processResourceRcl` ends the Resource
through `cancel()`, so it ends as `FAILED` rather than Python's `REJECTED`, and it sends a
`RESOURCE_ICL` back that the receiver logs as unknown. Python's `_rejected` (`Resource.py:1125`)
sets `REJECTED` and sends nothing. Doing that needs `ResourceConstants.REJECTED` corrected from
`0x00` (the same as `NONE`) to Python's `0x09`, a value LXMF-kt can see.
