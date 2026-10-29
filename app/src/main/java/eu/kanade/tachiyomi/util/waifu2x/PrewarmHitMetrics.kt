package eu.kanade.tachiyomi.util.waifu2x

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session-local instrumentation for how often reading lands on an already enhanced
 * page and how long the enhanced image takes to become visible otherwise. In-memory
 * only, no UI: a logcat summary is emitted every [SUMMARY_INTERVAL] pages so a
 * reading session can be reconstructed with `adb logcat -s PrewarmHit`.
 */
object PrewarmHitMetrics {

    private const val SUMMARY_INTERVAL = 20

    private val pendingChecks = ConcurrentHashMap<String, Long>()
    private val hits = AtomicInteger(0)
    private val misses = AtomicInteger(0)
    private val waitsMs = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val events = AtomicInteger(0)

    private fun key(mangaId: Long, chapterId: Long, pageIndex: Int) = "${mangaId}_${chapterId}_$pageIndex"

    /** First cache probe for a page; the anchor for the enhanced-visible wait time. */
    fun recordCheck(mangaId: Long, chapterId: Long, pageIndex: Int) {
        pendingChecks.putIfAbsent(key(mangaId, chapterId, pageIndex), System.currentTimeMillis())
    }

    fun recordMiss(mangaId: Long, chapterId: Long, pageIndex: Int) {
        misses.incrementAndGet()
        onEvent()
        logcat(LogPriority.DEBUG) { "PrewarmHit: miss page $pageIndex (m=$mangaId c=$chapterId)" }
    }

    /** Enhanced source became visible; wait measured from the first probe. */
    fun recordHit(mangaId: Long, chapterId: Long, pageIndex: Int) {
        val startedAt = pendingChecks.remove(key(mangaId, chapterId, pageIndex))
        val wait = startedAt?.let { System.currentTimeMillis() - it }
        if (wait != null) {
            waitsMs.add(wait)
        }
        hits.incrementAndGet()
        onEvent()
        logcat(LogPriority.DEBUG) {
            "PrewarmHit: hit page $pageIndex (m=$mangaId c=$chapterId wait=${wait ?: "n/a"}ms)"
        }
    }

    private fun onEvent() {
        if (events.incrementAndGet() % SUMMARY_INTERVAL == 0) {
            logSummary()
        }
    }

    private fun logSummary() {
        val sorted = waitsMs.sorted()
        fun percentile(p: Double): Long =
            if (sorted.isEmpty()) -1 else sorted[((sorted.size - 1) * p).toInt().coerceAtLeast(0)]
        logcat(LogPriority.INFO) {
            "PrewarmHit: session hits=${hits.get()} misses=${misses.get()} " +
                "hitRate=${hits.get() + misses.get().let {
                    if (it == 0) 0 else hits.get() * 100 / (hits.get() + it)
                }}% " +
                "waitP50=${percentile(0.5)}ms waitP95=${percentile(0.95)}ms samples=${sorted.size}"
        }
    }
}
