package eu.kanade.tachiyomi.util.system

import android.os.Process
import kotlin.math.roundToInt

/**
 * CPU usage of this process, sampled from the kernel's accounting for its threads.
 *
 * The reader draws it as a small bar in the corner so the cost of a quality preset is visible while
 * reading. Only this process is measured: no permission is needed and no data from other apps is
 * exposed. GPU and NPU work is not part of the number, which is why the label says CPU and not
 * "performance".
 *
 * Usage is expressed against the whole device (all cores), so 100% means every core is busy. A
 * single-threaded workload therefore reads about `100 / cores`.
 */
class ProcessCpuSampler(
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    private val cpuMillis: () -> Long = { Process.getElapsedCpuTime() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var lastCpuMillis: Long = -1L
    private var lastSampleNanos: Long = 0L

    /** Usage in percent, or null until a second sample exists. */
    fun sample(): Int? {
        val cpu = try {
            cpuMillis()
        } catch (_: Exception) {
            return null
        }
        val now = nanoTime()
        val previousCpu = lastCpuMillis
        val previousNanos = lastSampleNanos
        lastCpuMillis = cpu
        lastSampleNanos = now

        if (previousCpu < 0) return null
        // The counter is cumulative for the process; a smaller value means it restarted.
        if (cpu < previousCpu) return null
        val elapsedSeconds = (now - previousNanos) / NANOS_PER_SECOND
        if (elapsedSeconds < MIN_SAMPLE_SECONDS) return null

        val busyCpuSeconds = (cpu - previousCpu) / MILLIS_PER_SECOND
        return ((busyCpuSeconds / (elapsedSeconds * cores)) * 100).roundToInt().coerceIn(0, 100)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MILLIS_PER_SECOND = 1_000.0
        const val MIN_SAMPLE_SECONDS = 0.05
    }
}
