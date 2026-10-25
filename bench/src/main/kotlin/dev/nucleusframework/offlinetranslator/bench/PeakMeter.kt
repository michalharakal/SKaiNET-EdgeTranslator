package dev.nucleusframework.offlinetranslator.bench

import dev.nucleusframework.systeminfo.SystemInfo
import java.lang.management.ManagementFactory

/**
 * Samples peak JVM heap and process RSS on a daemon thread — the same two calls
 * `SystemMeters.kt` uses for the UI meter, minus the Compose.
 */
class PeakMeter(private val intervalMs: Long = 250) {
    @Volatile private var running = false

    @Volatile var peakHeapBytes: Long = 0
        private set

    @Volatile var peakRssBytes: Long = 0
        private set

    private var thread: Thread? = null

    fun start(): PeakMeter {
        running = true
        thread = Thread {
            val heap = ManagementFactory.getMemoryMXBean()
            val pid = ProcessHandle.current().pid()
            while (running) {
                peakHeapBytes = maxOf(peakHeapBytes, heap.heapMemoryUsage.used)
                val rss = runCatching { SystemInfo.process(pid)?.memory ?: 0L }.getOrDefault(0L)
                peakRssBytes = maxOf(peakRssBytes, rss)
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "bench-peak-meter"
            start()
        }
        return this
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1_000)
    }
}
