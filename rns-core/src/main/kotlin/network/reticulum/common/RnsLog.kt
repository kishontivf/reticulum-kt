package network.reticulum.common

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where this library's output goes.
 *
 * Before this existed every diagnostic in the stack was a bare `println`. That has three costs: it
 * cannot be routed anywhere — a file, a host's logger, a test — it cannot be filtered by level or
 * area, and it cannot be turned off, so a release build pays for every string it builds. On Android
 * it lands in logcat under `System.out`, undifferentiated from everything else the process prints.
 *
 * A sink fixes all three without deciding anything for the host: nothing is installed by default,
 * so a library user who wants output installs one and a user who wants silence does nothing at all.
 *
 * **Messages are built lazily.** Every entry point takes a lambda, so a line that is not emitted
 * costs nothing beyond the branch — which matters on the packet paths, where a formatted string per
 * packet is a real expense.
 */
object RnsLog {

    private val sinks = CopyOnWriteArrayList<Sink>()

    /**
     * Below this, nothing is emitted and no message is built.
     *
     * `Info` by default: the packet-level traffic is `Trace` and `Debug`, and a host that wants it
     * is asking for a great deal of output on purpose.
     */
    @Volatile
    var level: Level = Level.Info

    fun add(sink: Sink) {
        sinks += sink
    }

    /**
     * Whether a line at [candidate] would be emitted.
     *
     * For the rare caller that must *do work* to produce a diagnostic at all — walk a table, format
     * a snapshot — and needs to skip the work rather than just the string.
     */
    fun isEnabled(candidate: Level): Boolean =
        candidate >= level && sinks.isNotEmpty()

    fun debug(area: String, message: () -> String): Unit =
        emit(Level.Debug, area, null, message)

    fun info(area: String, message: () -> String): Unit =
        emit(Level.Info, area, null, message)

    fun warn(area: String, error: Throwable? = null, message: () -> String): Unit =
        emit(Level.Warning, area, error, message)

    fun error(area: String, error: Throwable? = null, message: () -> String): Unit =
        emit(Level.Error, area, error, message)

    private fun emit(level: Level, area: String, error: Throwable?, message: () -> String) {
        if (!isEnabled(level)) return

        val text = message()

        sinks.forEach { sink ->
            // One bad sink must not silence the others, or take down the call site it was logging
            // from. A logging facade that can throw is worse than no logging at all.
            runCatching { sink.log(level, area, text, error) }
        }
    }

    /** What a host installs to receive the library's output. */
    fun interface Sink {

        fun log(level: Level, area: String, message: String, error: Throwable?)
    }

    enum class Level { Trace, Debug, Info, Warning, Error }
}
