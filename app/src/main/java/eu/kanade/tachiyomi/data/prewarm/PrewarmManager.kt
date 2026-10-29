@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.prewarm

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.PrewarmCacheIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline prewarm queue: enhances pages of downloaded chapters ahead of reading so
 * opening a chapter lands directly on cached super-resolution output.
 *
 * Pages are dispatched one at a time through ImageEnhancer.enhanceLazy at preload
 * priority; the reading pipeline keeps absolute priority and a vanished request
 * (reader reset, config change, decode failure) is a normal event, never an error
 * to fight. Completion is tracked by polling the cache (isCached/isSkipped) - the
 * ground truth - while CacheEvent.Saved/Skipped/Cleared only invalidate that state.
 *
 * Constraints: the queue only advances while charging (configurable) and the reader
 * is closed; otherwise the current chapter is paused and resumes automatically. A
 * cache-wide clear (configuration change) bumps the session generation and
 * re-evaluates every entry against the new config hash. The queue is in-memory by
 * design: pages already written to disk survive a process death as plain cache.
 */
@Inject
@SingleIn(AppScope::class)
class PrewarmManager(
    private val constraints: PrewarmConstraints,
    private val pageSource: PrewarmPageSource,
    private val cacheBridge: PrewarmCacheBridge,
    private val enhancer: PrewarmEnhancer,
    private val configSource: PrewarmConfigSource,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    private val _queueState = MutableStateFlow<List<PrewarmItem>>(emptyList())
    val queueState: StateFlow<List<PrewarmItem>> = _queueState.asStateFlow()

    /**
     * Ordering currently applied to the chapters inside each series block. Dragging switches it to
     * [PrewarmSort.CUSTOM] because a manual arrangement cannot be described by a comparator.
     */
    private val _sortMode = MutableStateFlow(PrewarmSort.CUSTOM)
    val sortMode: StateFlow<PrewarmSort> = _sortMode.asStateFlow()

    /** Stamp source for "join order" sorting; incremented per queued chapter. */
    private val queueSequence = java.util.concurrent.atomic.AtomicLong(0)

    private val _stats = MutableStateFlow(PrewarmSessionStats())
    val stats: StateFlow<PrewarmSessionStats> = _stats.asStateFlow()

    private val _lastCompletion = MutableStateFlow<PrewarmCompletionSummary?>(null)

    /** Result of the most recent run that reached an idle queue, for "last run" lines. */
    val lastCompletion: StateFlow<PrewarmCompletionSummary?> = _lastCompletion.asStateFlow()

    /** Chapters a prewarm run finished, so the reader can say a chapter is already enhanced. */
    val prewarmedChapterIds: StateFlow<Set<Long>> = _queueState
        .map { queue -> queue.filter { it.status == PrewarmStatus.DONE }.map { it.chapterId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Per-run counters, reset every time a run is summarised.
    private var runChapters = 0
    private var runPages = 0

    private val _manualPaused = MutableStateFlow(false)
    val manualPaused: StateFlow<Boolean> = _manualPaused.asStateFlow()

    /** Manga/Chapter references backing queue items; cleaned up when items leave the queue. */
    private val chapterRefs = ConcurrentHashMap<Long, Pair<Manga, Chapter>>()

    private val workSignal = Channel<Unit>(Channel.CONFLATED)
    private val workerActive = AtomicBoolean(false)
    private val sessionGeneration = AtomicInteger(0)

    /**
     * Session-scoped "prewarm now" exemption for the charger requirement. Set by
     * [prewarmNowOnce], cleared as soon as the queue holds no active entry, so the
     * configured constraint always applies again after a batch or an app restart.
     * It never overrides the other gates (reader open, manual pause, switches off).
     */
    private val chargeExemptOnce = AtomicBoolean(false)

    init {
        scope.launch {
            cacheBridge.events.collect { event ->
                if (event is ImageEnhancementCache.CacheEvent.Cleared) onCacheCleared()
            }
        }
        // Constraint pauses can orphan a chapter mid-run (the worker returns instead of
        // parking when a page reports GATED). No callback exists for e.g. the charger
        // being plugged back in, so a slow watchdog requeues constraint-paused entries
        // once the gate opens again. Manual pauses are never touched by this.
        scope.launch {
            while (true) {
                delay(CONSTRAINT_POLL_MS)
                if (hasConstraintPausedItems() && currentGateReason() == null) {
                    resumeConstraintPaused()
                }
            }
        }
    }

    // region Public API

    /**
     * Adds downloaded chapters to the queue. Chapters already queued in an active
     * state are ignored; DONE/SKIPPED/ERROR entries are replaced for a catch-up run
     * that only processes pages missing from the cache.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>): Int {
        if (chapters.isEmpty()) return 0
        val candidates = chapters.distinctBy { it.id }
        val active = _queueState.value
            .filter { it.status in ACTIVE_STATUSES }
            .mapTo(HashSet()) { it.chapterId }
        val toQueue = candidates.filter { it.id !in active }
        if (toQueue.isEmpty()) return 0

        _queueState.update { list ->
            val stillActive = list
                .filter { it.status in ACTIVE_STATUSES }
                .mapTo(HashSet()) { it.chapterId }
            val fresh = toQueue
                .filter { it.id !in stillActive }
                .map { chapter ->
                    chapterRefs[chapter.id] = manga to chapter
                    PrewarmItem(
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        chapterId = chapter.id,
                        chapterName = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        queuedAt = queueSequence.incrementAndGet(),
                    )
                }
            val replacedIds = fresh.mapTo(HashSet()) { it.chapterId }
            list.filterNot { it.chapterId in replacedIds } + fresh
        }
        // A sort chosen earlier also applies to whatever arrives later, so the visible order never
        // depends on when a chapter happened to be queued.
        _sortMode.value.takeIf { it != PrewarmSort.CUSTOM }?.let { sort ->
            _queueState.update { PrewarmQueueOrder.sorted(it, sort) }
        }

        logcat(LogPriority.INFO) { "Prewarm: queued ${toQueue.size} chapter(s) for \"${manga.title}\"" }
        signalWork()
        return toQueue.size
    }

    fun cancel(item: PrewarmItem) {
        cancelChapters(item.mangaId, listOf(item.chapterId))
    }

    fun cancelSeries(mangaId: Long) {
        cancelChapters(mangaId, null)
    }

    fun clearQueue() {
        cancelChapters(null, null)
    }

    /**
     * Reorders the chapters inside every series block. Blocks themselves keep their order, and a
     * chapter never leaves its series.
     */
    fun sortQueue(sort: PrewarmSort) {
        if (sort == PrewarmSort.CUSTOM) return
        _queueState.update { PrewarmQueueOrder.sorted(it, sort) }
        _sortMode.value = sort
    }

    /** Drag within a series; the target clamps to the edges of that series. */
    fun moveChapter(chapterId: Long, toIndexInSeries: Int) {
        _queueState.update { PrewarmQueueOrder.moveChapter(it, chapterId, toIndexInSeries) }
        _sortMode.value = PrewarmSort.CUSTOM
    }

    /** Drag a series header to reorder the blocks. */
    fun moveSeries(mangaId: Long, toIndex: Int) {
        _queueState.update { PrewarmQueueOrder.moveSeries(it, mangaId, toIndex) }
        _sortMode.value = PrewarmSort.CUSTOM
    }

    fun pause() {
        if (!_manualPaused.compareAndSet(expect = false, update = true)) return
        logcat(LogPriority.INFO) { "Prewarm: paused manually" }
    }

    fun resume() {
        if (!_manualPaused.compareAndSet(expect = true, update = false)) return
        logcat(LogPriority.INFO) { "Prewarm: resumed manually" }
    }

    /**
     * One-shot "prewarm now" permission from the queue screen: it lets the queue run
     * without the charger for as long as it holds active work. The setting is never
     * written, and the waiver is dropped when the batch drains or the app restarts, so
     * the default constraint comes back on its own. Other gates still apply - opening
     * the reader keeps pausing the queue.
     */
    fun prewarmNowOnce() {
        chargeExemptOnce.set(true)
        logcat(LogPriority.INFO) { "Prewarm: charger requirement waived for the current batch" }
        if (currentGateReason() == null) {
            resumeConstraintPaused()
        } else {
            signalWork()
        }
    }

    /** True while the one-shot charger waiver from [prewarmNowOnce] is in effect. */
    val isChargeWaivedOnce: Boolean
        get() = chargeExemptOnce.get()

    /** Reported by ReaderActivity: the reader being in the foreground pauses the queue. */
    fun onReaderActive(active: Boolean) {
        if (constraints.isReaderActive() == active) return
        constraints.setReaderActive(active)
        logcat(LogPriority.DEBUG) { "Prewarm: reader ${if (active) "opened, pausing" else "closed, resuming"}" }
        if (!active) resumeConstraintPaused()
    }

    // endregion

    // region Cache lifecycle

    /**
     * A cache-wide clear (config change, manual wipe, or a single corrupt entry
     * discard) invalidates everything we believed about cached pages. Re-evaluate
     * every entry: each chapter run recomputes its remaining pages against the
     * current config hash, so this is inherently idempotent. Budget- and
     * availability-based skips are retried too - the clear may have freed space or
     * the chapter may be back on disk. Only auto page split stays skipped, since it
     * is a device capability rather than a cache state.
     */
    private fun onCacheCleared() {
        sessionGeneration.incrementAndGet()
        logcat(LogPriority.INFO) { "Prewarm: enhancement cache cleared, re-evaluating queue" }
        _queueState.update { list ->
            list.map { item ->
                if (item.status == PrewarmStatus.SKIPPED &&
                    item.skipReason == PrewarmSkipReason.AUTO_SPLIT_UNSUPPORTED
                ) {
                    item
                } else {
                    item.copy(
                        status = PrewarmStatus.QUEUED,
                        totalPages = 0,
                        pagesCompleted = 0,
                        pagesCachedHits = 0,
                        pagesFailed = 0,
                        pauseReason = PrewarmPauseReason.NONE,
                        skipReason = null,
                    )
                }
            }
        }
        signalWork()
    }

    // endregion

    // region Worker

    private fun signalWork() {
        workSignal.trySend(Unit)
        startWorkerIfNeeded()
    }

    private fun startWorkerIfNeeded() {
        if (!workerActive.compareAndSet(false, true)) return
        scope.launch {
            try {
                workerLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Prewarm: worker crashed" }
            } finally {
                workerActive.set(false)
                if (_queueState.value.any { it.status == PrewarmStatus.QUEUED }) startWorkerIfNeeded()
            }
        }
    }

    private suspend fun workerLoop() {
        while (true) {
            workSignal.receive()
            while (true) {
                val item = _queueState.value.firstOrNull { it.status == PrewarmStatus.QUEUED } ?: break
                runChapter(item)
            }
        }
    }

    private suspend fun runChapter(item: PrewarmItem) {
        val gen = sessionGeneration.get()
        updateItem(item.chapterId) { it.copy(status = PrewarmStatus.RUNNING, pauseReason = PrewarmPauseReason.NONE) }

        val ref = chapterRefs[item.chapterId]
        if (ref == null) {
            // The item was removed while the worker was parked; drop it entirely.
            _queueState.update { list -> list.filterNot { it.chapterId == item.chapterId } }
            return
        }
        val (manga, chapter) = ref
        logcat(LogPriority.INFO) { "Prewarm: chapter start \"${manga.title}\" - \"${chapter.name}\"" }

        when (awaitGate(item.chapterId, gen)) {
            GateResult.REMOVED, GateResult.INVALIDATED -> return
            GateResult.READY -> Unit
        }
        if (configSource.isAutoSplitEnabled()) {
            logcat(LogPriority.INFO) { "Prewarm: skipping \"${chapter.name}\" (auto page split)" }
            finishSkipped(item.chapterId, PrewarmSkipReason.AUTO_SPLIT_UNSUPPORTED)
            return
        }

        val chapterPages = try {
            pageSource.enumerate(manga, chapter)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Prewarm: chapter not prewarmable \"${chapter.name}\"" }
            finishSkipped(item.chapterId, PrewarmSkipReason.CHAPTER_UNAVAILABLE)
            return
        }

        chapterPages.use { pages ->
            val configHash = configSource.currentConfigHash()
            cacheBridge.trimCache()
            val cacheSize = cacheBridge.cacheSizeBytes()
            if (cacheSize > CACHE_BUDGET_SOFT_LIMIT) {
                logcat(LogPriority.WARN) {
                    "Prewarm: cache budget exceeded ($cacheSize bytes), skipping \"${chapter.name}\""
                }
                finishSkipped(item.chapterId, PrewarmSkipReason.CACHE_BUDGET_EXCEEDED)
                return
            }

            val pageList = pages.pages.distinctBy { it.index }
            // A tall page is expanded into one unit per reader segment right here, in the
            // consumer: the page source keeps returning one element per logical page, because the
            // "already warmed" badge reads that count as the chapter's page count.
            val units = pageList.flatMap { expandUnits(it) }
            val unitsByPage = units.groupBy { it.pageIndex }
            val settledPages = unitsByPage
                .filterValues { pageUnits -> pageUnits.all { isSettled(manga, chapter, it, configHash) } }
                .keys
            val pendingUnits = units.filterNot { isSettled(manga, chapter, it, configHash) }
            val cacheHits = settledPages.size
            updateItem(item.chapterId) {
                it.copy(
                    totalPages = pageList.size,
                    pagesCompleted = cacheHits,
                    pagesCachedHits = cacheHits,
                )
            }
            _stats.update { it.copy(cacheHitPages = it.cacheHitPages + cacheHits) }
            if (pendingUnits.isEmpty()) {
                logcat(LogPriority.INFO) {
                    "Prewarm: \"${chapter.name}\" already fully cached (${pageList.size} pages)"
                }
                markWarmedIfComplete(manga, chapter, configHash, pageList.size, cacheHits, 0, units)
                finishDone(item.chapterId)
                return
            }
            logcat(LogPriority.INFO) {
                "Prewarm: \"${chapter.name}\" ${pendingUnits.size}/${units.size} units to enhance " +
                    "(${pageList.size} pages)"
            }

            // Breadth first: every page's first segment before any page's second one. The reader
            // only preloads the leading segment of the pages after the current one, so this order
            // makes the most pages land on an enhanced first frame soonest.
            val queue = pendingUnits.sortedWith { a, b ->
                val byVariant = a.variant.compareToCaseInsensitiveNaturalOrder(b.variant)
                if (byVariant != 0) byVariant else a.pageIndex.compareTo(b.pageIndex)
            }
            // Pages that already paid the inter-page cool-down. The pause exists to keep sustained
            // thermal load low, so it is charged per logical page: a tall page's segments are
            // processed back to back instead of each waiting 500ms.
            val pacedPages = HashSet<Int>()
            // Logical pages whose segments are all settled, so each is counted exactly once.
            val completedPages = settledPages.toMutableSet()

            var failed = 0
            for (page in queue) {
                when (awaitGate(item.chapterId, gen)) {
                    GateResult.REMOVED, GateResult.INVALIDATED -> return
                    GateResult.READY -> Unit
                }

                when (executePage(manga, chapter, page, configHash, gen)) {
                    PageOutcome.REMOVED, PageOutcome.INVALIDATED -> return
                    PageOutcome.GATED -> {
                        // Constraints broke mid-wait (e.g. the reader opened); the next
                        // resume re-enumerates, so uncached pages are retried naturally.
                        currentGateReason()?.let { pauseChapter(item.chapterId, it) }
                        return
                    }
                    PageOutcome.SUCCESS -> {
                        if (page.pageIndex !in completedPages &&
                            unitsByPage[page.pageIndex]?.all { isSettled(manga, chapter, it, configHash) } == true
                        ) {
                            completedPages += page.pageIndex
                            updateItem(item.chapterId) { it.copy(pagesCompleted = it.pagesCompleted + 1) }
                            _stats.update { it.copy(enhancedPages = it.enhancedPages + 1) }
                            runPages++
                        }
                        logcat(LogPriority.DEBUG) {
                            "Prewarm: page ${page.pageIndex}/${page.variant} done (\"${chapter.name}\")"
                        }
                    }
                    PageOutcome.FAILED -> {
                        failed++
                        updateItem(item.chapterId) { it.copy(pagesFailed = it.pagesFailed + 1) }
                        _stats.update { it.copy(failedPages = it.failedPages + 1) }
                        logcat(LogPriority.WARN) {
                            "Prewarm: page ${page.pageIndex}/${page.variant} failed (\"${chapter.name}\")"
                        }
                    }
                }
                if (pacedPages.add(page.pageIndex)) {
                    delay(PAGE_INTERVAL_MS)
                }
            }

            if (failed > 0) {
                updateItem(item.chapterId) {
                    it.copy(status = PrewarmStatus.ERROR, pauseReason = PrewarmPauseReason.NONE)
                }
                publishCompletionIfIdle()
                logcat(LogPriority.WARN) { "Prewarm: chapter \"${chapter.name}\" finished with $failed failed unit(s)" }
            } else {
                markWarmedIfComplete(
                    manga = manga,
                    chapter = chapter,
                    configHash = configHash,
                    totalPages = pageList.size,
                    completedPages = completedPages.size,
                    failedUnits = failed,
                    units = units,
                )
                finishDone(item.chapterId)
                logcat(LogPriority.INFO) { "Prewarm: chapter \"${chapter.name}\" done (${pageList.size} pages)" }
            }
        }
    }

    /**
     * Advertises the chapter as warmed when this run left nothing behind.
     *
     * Skipped units are re-checked rather than counted during the loop: a page the enhancer
     * refused carries a skip marker instead of an image, which is settled but not enhanced, and a
     * marker for it would make the badge claim pages that are still originals. Cancelled and
     * interrupted runs return before reaching this, so they never mark anything.
     */
    private suspend fun markWarmedIfComplete(
        manga: Manga,
        chapter: Chapter,
        configHash: String,
        totalPages: Int,
        completedPages: Int,
        failedUnits: Int,
        units: List<EnhanceUnit>,
    ) {
        val skippedUnits = units.count {
            cacheBridge.isSkipped(manga.id, chapter.id, it.pageIndex, configHash, it.variant)
        }
        if (!PrewarmCacheIndex.shouldMarkWarmed(totalPages, completedPages, skippedUnits, failedUnits)) {
            return
        }
        runCatching { cacheBridge.markWarmed(manga.id, chapter.id, configHash) }
            .onSuccess {
                logcat(LogPriority.INFO) { "Prewarm: chapter \"${chapter.name}\" marked warmed for this configuration" }
            }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) { "Prewarm: failed to mark \"${chapter.name}\" warmed" }
            }
    }

    /** One dispatchable enhancement: a whole page ([PAGE_VARIANT]) or one reader segment of it. */
    private data class EnhanceUnit(
        val pageIndex: Int,
        val variant: String,
        val dataProvider: () -> Any?,
    )

    /**
     * Expands one logical page into its work units.
     *
     * A page the reader splits contributes one unit per segment, each carrying only that segment's
     * bytes and the variant the reader asks for; anything else stays a single whole-page unit.
     */
    private fun expandUnits(page: PrewarmPage): List<EnhanceUnit> {
        val segments = page.segments ?: return listOf(EnhanceUnit(page.index, PAGE_VARIANT, page.dataProvider))
        val segmentCount = try {
            segments.count()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Prewarm: failed to plan segments of page ${page.index}" }
            0
        }
        if (segmentCount <= 1) {
            return listOf(EnhanceUnit(page.index, PAGE_VARIANT, page.dataProvider))
        }
        return (0 until segmentCount).map { segmentIndex ->
            EnhanceUnit(
                pageIndex = page.index,
                variant = ImageUtil.readerSegmentVariant(segmentIndex),
                dataProvider = { segments.bytes(segmentIndex) },
            )
        }
    }

    /** Whether this unit already has an enhanced image (or was marked unprocessable). */
    private fun isSettled(
        manga: Manga,
        chapter: Chapter,
        unit: EnhanceUnit,
        configHash: String,
    ): Boolean {
        return cacheBridge.isCached(manga.id, chapter.id, unit.pageIndex, configHash, unit.variant) ||
            cacheBridge.isSkipped(manga.id, chapter.id, unit.pageIndex, configHash, unit.variant)
    }

    private enum class GateResult { READY, REMOVED, INVALIDATED }

    /** Suspends while constraints disallow work, keeping the item marked PAUSED. */
    private suspend fun awaitGate(chapterId: Long, gen: Int): GateResult {
        var wasPaused = false
        while (true) {
            if (isRemoved(chapterId)) return GateResult.REMOVED
            if (sessionGeneration.get() != gen) return GateResult.INVALIDATED
            val reason = currentGateReason()
            if (reason == null) {
                if (wasPaused) {
                    logcat(LogPriority.INFO) { "Prewarm: constraints satisfied, resuming queue" }
                }
                updateItem(chapterId) { it.copy(status = PrewarmStatus.RUNNING, pauseReason = PrewarmPauseReason.NONE) }
                // Any other chapter left paused by the same constraint is requeued here;
                // a watchdog covers the case where no live chapter reaches this point.
                resumeConstraintPaused()
                return GateResult.READY
            }
            wasPaused = true
            pauseChapter(chapterId, reason)
            delay(CONSTRAINT_POLL_MS)
        }
    }

    private fun currentGateReason(): PrewarmPauseReason? {
        // The "prewarm now" waiver belongs to the batch it was granted for: once no
        // active entry is left, drop it so the charger requirement applies again.
        if (chargeExemptOnce.get() && _queueState.value.none { it.status in ACTIVE_STATUSES }) {
            chargeExemptOnce.set(false)
        }
        val level = constraints.batteryLevel()
        return when {
            !configSource.isPrewarmEnabled() -> PrewarmPauseReason.PREWARM_DISABLED
            !configSource.isEnhancementEnabled() -> PrewarmPauseReason.ENHANCEMENT_DISABLED
            _manualPaused.value -> PrewarmPauseReason.MANUAL
            // Hard floor: no policy and no "prewarm now" waiver can override this.
            level != null && level < LOW_BATTERY_THRESHOLD -> PrewarmPauseReason.LOW_BATTERY
            constraints.isReaderActive() -> PrewarmPauseReason.READER_ACTIVE
            !chargeExemptOnce.get() &&
                !batteryPolicySatisfied(
                    configSource.batteryPolicy(),
                    constraints.isCharging(),
                    level,
                ) -> PrewarmPauseReason.WAITING_CHARGE
            else -> null
        }
    }

    private fun batteryPolicySatisfied(
        policy: PrewarmBatteryPolicy,
        charging: Boolean,
        level: Int?,
    ): Boolean = when (policy) {
        PrewarmBatteryPolicy.CHARGE_ONLY -> charging
        PrewarmBatteryPolicy.SUFFICIENT_CHARGE -> charging || (level != null && level > SUFFICIENT_BATTERY_THRESHOLD)
        PrewarmBatteryPolicy.ANY_TIME -> true
    }

    private enum class PageOutcome { SUCCESS, FAILED, GATED, REMOVED, INVALIDATED }

    /**
     * Enqueues one page and waits until the cache reflects it. The request itself is
     * allowed to vanish (reader reset, decode failure); it surfaces as FAILED after a
     * short grace period instead of blocking the chapter for the full timeout.
     */
    private suspend fun executePage(
        manga: Manga,
        chapter: Chapter,
        page: EnhanceUnit,
        configHash: String,
        gen: Int,
    ): PageOutcome {
        enhancer.enqueue(manga.id, chapter.id, page.pageIndex, page.variant, page.dataProvider)
        var polls = 0
        while (true) {
            if (isRemoved(chapter.id)) return PageOutcome.REMOVED
            if (sessionGeneration.get() != gen) return PageOutcome.INVALIDATED
            // React to a constraint break before checking completion: if the page did
            // finish anyway, the next pass counts it as a cache hit.
            if (currentGateReason() != null) return PageOutcome.GATED
            if (
                cacheBridge.isCached(manga.id, chapter.id, page.pageIndex, configHash, page.variant) ||
                cacheBridge.isSkipped(manga.id, chapter.id, page.pageIndex, configHash, page.variant)
            ) {
                return PageOutcome.SUCCESS
            }

            val inFlight = enhancer.hasRequest(manga.id, chapter.id, page.pageIndex, page.variant) ||
                enhancer.isActivelyProcessing(manga.id, chapter.id, page.pageIndex, page.variant)
            val elapsedMs = polls * PAGE_POLL_MS
            if (!inFlight && elapsedMs >= REQUEST_GONE_GRACE_MS) return PageOutcome.FAILED
            if (elapsedMs >= PAGE_TIMEOUT_MS) return PageOutcome.FAILED

            delay(PAGE_POLL_MS)
            polls++
        }
    }

    // endregion

    // region State helpers

    private fun cancelChapters(mangaId: Long?, chapterIds: List<Long>?): Int {
        val ids = chapterIds?.toHashSet()
        val predicate: (PrewarmItem) -> Boolean =
            { (mangaId == null || it.mangaId == mangaId) && (ids == null || it.chapterId in ids) }
        val removedItems = _queueState.value.filter(predicate)
        if (removedItems.isEmpty()) return 0
        _queueState.update { list -> list.filterNot(predicate) }
        removedItems.forEach { chapterRefs.remove(it.chapterId) }
        logcat(LogPriority.INFO) { "Prewarm: cancelled ${removedItems.size} item(s)" }
        return removedItems.size
    }

    private fun isRemoved(chapterId: Long): Boolean = _queueState.value.none { it.chapterId == chapterId }

    private fun hasConstraintPausedItems(): Boolean = _queueState.value.any {
        it.status == PrewarmStatus.PAUSED && it.pauseReason in CONSTRAINT_PAUSE_REASONS
    }

    /**
     * Moves chapters paused by an automatic constraint (reader open, no charger,
     * switches off) back into the queue once that constraint no longer applies.
     * Manual pauses are intentionally untouched - only resume() lifts them.
     */
    private fun resumeConstraintPaused() {
        val recovered = _queueState.value.count {
            it.status == PrewarmStatus.PAUSED && it.pauseReason in CONSTRAINT_PAUSE_REASONS
        }
        if (recovered == 0) return
        _queueState.update { list ->
            list.map {
                if (it.status == PrewarmStatus.PAUSED && it.pauseReason in CONSTRAINT_PAUSE_REASONS) {
                    it.copy(status = PrewarmStatus.QUEUED, pauseReason = PrewarmPauseReason.NONE)
                } else {
                    it
                }
            }
        }
        logcat(LogPriority.INFO) { "Prewarm: constraints cleared, requeueing $recovered paused chapter(s)" }
        signalWork()
    }

    private fun pauseChapter(chapterId: Long, reason: PrewarmPauseReason) {
        val item = _queueState.value.firstOrNull { it.chapterId == chapterId } ?: return
        if (item.status == PrewarmStatus.PAUSED && item.pauseReason == reason) return
        logcat(LogPriority.INFO) { "Prewarm: pausing \"${item.chapterName}\" ($reason)" }
        _queueState.update { list ->
            list.map {
                if (it.chapterId ==
                    chapterId
                ) {
                    it.copy(status = PrewarmStatus.PAUSED, pauseReason = reason)
                } else {
                    it
                }
            }
        }
    }

    private fun finishDone(chapterId: Long) {
        updateItem(chapterId) { it.copy(status = PrewarmStatus.DONE, pauseReason = PrewarmPauseReason.NONE) }
        runChapters++
        publishCompletionIfIdle()
    }

    private fun finishSkipped(chapterId: Long, reason: PrewarmSkipReason) {
        updateItem(chapterId) {
            it.copy(status = PrewarmStatus.SKIPPED, skipReason = reason, pauseReason = PrewarmPauseReason.NONE)
        }
        publishCompletionIfIdle()
    }

    /**
     * Records what the run accomplished once nothing is left to do. The counters only
     * reset here, so a queue that is cleared or paused keeps its partial progress.
     */
    private fun publishCompletionIfIdle() {
        if (_queueState.value.any { it.status in ACTIVE_STATUSES }) return
        if (runChapters == 0 && runPages == 0) return
        _lastCompletion.value = PrewarmCompletionSummary(
            chapterCount = runChapters,
            pageCount = runPages,
            finishedAt = System.currentTimeMillis(),
        )
        runChapters = 0
        runPages = 0
    }

    private fun updateItem(chapterId: Long, transform: (PrewarmItem) -> PrewarmItem) {
        _queueState.update { list -> list.map { if (it.chapterId == chapterId) transform(it) else it } }
    }

    // endregion

    companion object {
        /**
         * Cache variant of a page that is displayed whole (not split into reader segments).
         * Every other unit uses the variant the reader asks for, see [PrewarmPage.variant].
         */
        const val PAGE_VARIANT = ""

        // Polling cadences. Completion is checked every 500ms; constraints every 2s
        // (sticky battery broadcast read + switches).
        const val PAGE_POLL_MS = 500L
        const val CONSTRAINT_POLL_MS = 2_000L

        // Fixed cool-down between pages to keep sustained thermal load low.
        const val PAGE_INTERVAL_MS = 500L

        // Per-page wall clock budget; a large webtoon page under NPU warm-up can be slow.
        const val PAGE_TIMEOUT_MS = 120_000L

        // How long to wait after the request disappeared from the enhancer before
        // declaring the page failed.
        const val REQUEST_GONE_GRACE_MS = 3_000L

        // 90% of ImageEnhancementCache's 3GB cap; leave headroom for reading-time work.
        val CACHE_BUDGET_SOFT_LIMIT: Long = 3L * 1024 * 1024 * 1024 * 9 / 10

        // Below this the device stops prewarming whatever the policy says; above it,
        // "sufficient charge" treats an unplugged device as usable.
        const val LOW_BATTERY_THRESHOLD = 20
        const val SUFFICIENT_BATTERY_THRESHOLD = 50

        private val ACTIVE_STATUSES =
            setOf(PrewarmStatus.QUEUED, PrewarmStatus.RUNNING, PrewarmStatus.PAUSED)

        // Pauses the queue lifts on its own once the underlying constraint clears.
        private val CONSTRAINT_PAUSE_REASONS = setOf(
            PrewarmPauseReason.READER_ACTIVE,
            PrewarmPauseReason.WAITING_CHARGE,
            PrewarmPauseReason.LOW_BATTERY,
            PrewarmPauseReason.PREWARM_DISABLED,
            PrewarmPauseReason.ENHANCEMENT_DISABLED,
        )
    }
}
