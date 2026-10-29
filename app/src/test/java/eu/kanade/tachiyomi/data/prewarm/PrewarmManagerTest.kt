package eu.kanade.tachiyomi.data.prewarm

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalCoroutinesApi::class)
class PrewarmManagerTest {

    private val manga = Manga.create().copy(id = 1L, title = "Test Manga", source = 1L)

    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = 1L, name = "Chapter $id", url = "c$id")

    private class FakeConstraints(
        var charging: Boolean = true,
        var level: Int? = null,
    ) : PrewarmConstraints {
        private var active = false
        override fun isCharging(): Boolean = charging
        override fun batteryLevel(): Int? = level
        override fun isReaderActive(): Boolean = active
        override fun setReaderActive(active: Boolean) {
            this.active = active
        }
    }

    private class FakeConfigSource(
        var prewarmEnabled: Boolean = true,
        var enhancementEnabled: Boolean = true,
        var autoSplit: Boolean = false,
        var batteryPolicy: PrewarmBatteryPolicy = PrewarmBatteryPolicy.CHARGE_ONLY,
    ) : PrewarmConfigSource {
        override fun isPrewarmEnabled(): Boolean = prewarmEnabled
        override fun isEnhancementEnabled(): Boolean = enhancementEnabled
        override fun isAutoSplitEnabled(): Boolean = autoSplit
        override fun batteryPolicy(): PrewarmBatteryPolicy = batteryPolicy
        override fun currentConfigHash(): String = "test-hash"
    }

    private class FakeCacheBridge : PrewarmCacheBridge {
        val eventsFlow = MutableSharedFlow<ImageEnhancementCache.CacheEvent>(extraBufferCapacity = 64)
        val cached = HashSet<String>()
        val skipped = HashSet<String>()
        val warmed = HashSet<String>()
        var sizeBytes = 0L

        override val events = eventsFlow

        override fun isCached(
            mangaId: Long,
            chapterId: Long,
            pageIndex: Int,
            configHash: String,
            pageVariant: String,
        ): Boolean = key(mangaId, chapterId, pageIndex, configHash, pageVariant) in cached

        override fun isSkipped(
            mangaId: Long,
            chapterId: Long,
            pageIndex: Int,
            configHash: String,
            pageVariant: String,
        ): Boolean = key(mangaId, chapterId, pageIndex, configHash, pageVariant) in skipped

        override fun currentConfigHash(): String = "test-hash"

        override fun trimCache() = Unit

        override fun cacheSizeBytes(): Long = sizeBytes

        override suspend fun markWarmed(mangaId: Long, chapterId: Long, configHash: String) {
            warmed += warmedKey(mangaId, chapterId, configHash)
        }

        /** True when a run advertised the chapter as fully enhanced for [configHash]. */
        fun wasMarkedWarmed(
            mangaId: Long,
            chapterId: Long,
            configHash: String = "test-hash",
        ): Boolean = warmedKey(mangaId, chapterId, configHash) in warmed

        private fun warmedKey(mangaId: Long, chapterId: Long, configHash: String) =
            "${mangaId}_${chapterId}_$configHash"

        /** Segments of one page are separate entries, exactly like the real cache. */
        fun key(
            mangaId: Long,
            chapterId: Long,
            pageIndex: Int,
            configHash: String,
            pageVariant: String = "",
        ) = "${mangaId}_${chapterId}_${pageIndex}_${pageVariant}_$configHash"
    }

    private class FakeEnhancer(
        private val cache: FakeCacheBridge,
    ) : PrewarmEnhancer {
        val enqueued = mutableListOf<Int>()

        /** Work units in dispatch order; a page displayed whole has an empty variant. */
        val enqueuedUnits = mutableListOf<Pair<Int, String>>()
        var failingPages: Set<Int> = emptySet()

        /** Lets a test flip constraints at a precise point of the run. */
        var onPageEnqueued: ((Int) -> Unit)? = null

        /** Pages whose completion is withheld until removed, simulating slow work. */
        val deferredPages = mutableSetOf<Int>()

        override fun enqueue(
            mangaId: Long,
            chapterId: Long,
            pageIndex: Int,
            pageVariant: String,
            dataProvider: () -> Any?,
        ) {
            enqueued += pageIndex
            enqueuedUnits += pageIndex to pageVariant
            if (pageIndex !in failingPages && pageIndex !in deferredPages) {
                cache.cached += cache.key(mangaId, chapterId, pageIndex, "test-hash", pageVariant)
            }
            onPageEnqueued?.invoke(pageIndex)
        }

        override fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): Boolean = false

        override fun isActivelyProcessing(
            mangaId: Long,
            chapterId: Long,
            pageIndex: Int,
            pageVariant: String,
        ): Boolean = false

        override fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String) = Unit
    }

    private class FakePageSource(
        private val pageCounts: Map<Long, Int>,
        private val missingChapters: Set<Long> = emptySet(),
        private val segmentsPerPage: Int = 0,
    ) : PrewarmPageSource {
        override suspend fun enumerate(manga: Manga, chapter: Chapter): PrewarmChapterPages {
            if (chapter.id in missingChapters) throw RuntimeException("not on disk")
            val count = pageCounts[chapter.id] ?: throw RuntimeException("no page count for ${chapter.id}")
            // One element per logical page, exactly like the real source: segments are exposed
            // through PrewarmPage.segments and expanded by the manager.
            return PrewarmChapterPages(
                (0 until count).map { pageIndex ->
                    PrewarmPage(
                        index = pageIndex,
                        dataProvider = { null },
                        segments = FakeSegments(segmentsPerPage),
                    )
                },
            )
        }
    }

    /** Reader segments of a fake page; 0/1 means "displayed whole". */
    private class FakeSegments(private val segmentCount: Int) : PrewarmPageSegments {
        override fun count(): Int = segmentCount

        override fun bytes(segmentIndex: Int): Any? = null
    }

    private class Fixture(
        val cache: FakeCacheBridge = FakeCacheBridge(),
        val constraints: FakeConstraints = FakeConstraints(),
        val config: FakeConfigSource = FakeConfigSource(),
        pageCounts: Map<Long, Int> = emptyMap(),
        missingChapters: Set<Long> = emptySet(),
        segmentsPerPage: Int = 0,
    ) {
        val enhancer = FakeEnhancer(cache)
        val pageSource = FakePageSource(pageCounts, missingChapters, segmentsPerPage)
    }

    @Test
    fun `pauses while not charging and resumes when plugged`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = false
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(2_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.PAUSED
        manager.queueState.value.single().pauseReason shouldBe PrewarmPauseReason.WAITING_CHARGE
        fixture.enhancer.enqueued shouldBe emptyList()

        fixture.constraints.charging = true
        advanceTimeBy(60_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)
        manager.stats.value.enhancedPages shouldBe 2
    }

    @Test
    fun `completes immediately when every page is already cached`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.cache.cached += fixture.cache.key(1L, 10L, 0, "test-hash")
        fixture.cache.cached += fixture.cache.key(1L, 10L, 1, "test-hash")
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(5_000)
        runCurrent()
        val item = manager.queueState.value.single()
        item.status shouldBe PrewarmStatus.DONE
        item.totalPages shouldBe 2
        item.pagesCachedHits shouldBe 2
        fixture.enhancer.enqueued shouldBe emptyList()
        manager.stats.value.cacheHitPages shouldBe 2
    }

    @Test
    fun `resets entries when the cache is cleared and re-enhances missing pages`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))
        advanceTimeBy(10_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)

        fixture.cache.cached.clear()
        fixture.cache.eventsFlow.tryEmit(ImageEnhancementCache.CacheEvent.Cleared)

        // The queue resets and re-runs the chapter; the worker may already be past the
        // QUEUED state by the time we observe it, so assert the observable outcome.
        advanceTimeBy(20_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1, 0, 1)
    }

    @Test
    fun `deduplicates active chapters in the queue`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 1))
        fixture.constraints.charging = false
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))
        manager.queueChapters(manga, listOf(chapter(10L)))

        runCurrent()
        manager.queueState.value.size shouldBe 1
    }

    @Test
    fun `records page failures and finishes the chapter with an error`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.enhancer.failingPages = setOf(1)
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(15_000)
        runCurrent()
        val item = manager.queueState.value.single()
        item.status shouldBe PrewarmStatus.ERROR
        item.pagesCompleted shouldBe 1
        item.pagesFailed shouldBe 1
        manager.stats.value.enhancedPages shouldBe 1
        manager.stats.value.failedPages shouldBe 1
    }

    @Test
    fun `skips chapters when auto page split is enabled`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 1))
        fixture.config.autoSplit = true
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(5_000)
        runCurrent()
        val item = manager.queueState.value.single()
        item.status shouldBe PrewarmStatus.SKIPPED
        item.skipReason shouldBe PrewarmSkipReason.AUTO_SPLIT_UNSUPPORTED
        fixture.enhancer.enqueued shouldBe emptyList()
    }

    @Test
    fun `skips chapters that are not available on disk`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 1), missingChapters = setOf(10L))
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(5_000)
        runCurrent()
        val item = manager.queueState.value.single()
        item.status shouldBe PrewarmStatus.SKIPPED
        item.skipReason shouldBe PrewarmSkipReason.CHAPTER_UNAVAILABLE
    }

    @Test
    fun `cancel removes the item and stops work`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = false
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))
        runCurrent()
        manager.cancel(manager.queueState.value.single())

        advanceTimeBy(5_000)
        runCurrent()
        manager.queueState.value shouldBe emptyList()
        fixture.enhancer.enqueued shouldBe emptyList()
    }

    @Test
    fun `resumes automatically after the reader closes mid-chapter`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        // The reader opens while the second page is still enhancing, simulating the
        // user glancing at the reader during a prewarm run.
        fixture.enhancer.deferredPages += 1
        var readerOpenedOnce = false
        fixture.enhancer.onPageEnqueued = { pageIndex ->
            if (pageIndex == 1 && !readerOpenedOnce) {
                readerOpenedOnce = true
                fixture.constraints.setReaderActive(true)
            }
        }
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(10_000)
        runCurrent()
        manager.queueState.value.single().let {
            it.status shouldBe PrewarmStatus.PAUSED
            it.pauseReason shouldBe PrewarmPauseReason.READER_ACTIVE
        }

        fixture.constraints.setReaderActive(false)
        fixture.enhancer.deferredPages.remove(1)
        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        // Page 1 was dispatched once before the pause and once after the recovery.
        fixture.enhancer.enqueued shouldBe listOf(0, 1, 1)
    }

    @Test
    fun `retries budget-skipped chapters after the cache is cleared`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.cache.sizeBytes = 2_900_000_000L // over the 90% budget
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(5_000)
        runCurrent()
        manager.queueState.value.single().let {
            it.status shouldBe PrewarmStatus.SKIPPED
            it.skipReason shouldBe PrewarmSkipReason.CACHE_BUDGET_EXCEEDED
        }
        fixture.enhancer.enqueued shouldBe emptyList()

        // A cache-wide clear (manual cleanup or a config change) frees the budget;
        // the skipped chapter is re-evaluated instead of staying skipped forever.
        fixture.cache.sizeBytes = 0L
        fixture.cache.eventsFlow.tryEmit(ImageEnhancementCache.CacheEvent.Cleared)
        advanceTimeBy(20_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)
    }

    @Test
    fun `resumes the only paused chapter when charging recovers`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        // No live chapter remains after the gate breaks mid-run, so the watchdog is
        // the only recovery path for this scenario.
        fixture.enhancer.deferredPages += 1
        var unpluggedOnce = false
        fixture.enhancer.onPageEnqueued = { pageIndex ->
            if (pageIndex == 1 && !unpluggedOnce) {
                unpluggedOnce = true
                fixture.constraints.charging = false
            }
        }
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(10_000)
        runCurrent()
        manager.queueState.value.single().let {
            it.status shouldBe PrewarmStatus.PAUSED
            it.pauseReason shouldBe PrewarmPauseReason.WAITING_CHARGE
        }

        fixture.constraints.charging = true
        fixture.enhancer.deferredPages.remove(1)
        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1, 1)
    }

    @Test
    fun `manual pause persists until resume is called`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))
        manager.pause()

        advanceTimeBy(10_000)
        runCurrent()
        manager.queueState.value.single().let {
            it.status shouldBe PrewarmStatus.PAUSED
            it.pauseReason shouldBe PrewarmPauseReason.MANUAL
        }

        manager.resume()
        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)
    }

    @Test
    fun `prewarmNowOnce runs a charger-paused queue without plugging in`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = false
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(2_000)
        runCurrent()
        manager.queueState.value.single().let {
            it.status shouldBe PrewarmStatus.PAUSED
            it.pauseReason shouldBe PrewarmPauseReason.WAITING_CHARGE
        }
        fixture.enhancer.enqueued shouldBe emptyList()

        manager.prewarmNowOnce()
        advanceTimeBy(30_000)
        runCurrent()

        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)
        manager.stats.value.enhancedPages shouldBe 2
    }

    @Test
    fun `prewarmNowOnce does not bypass the reader gate`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = false
        fixture.constraints.setReaderActive(true)
        val manager = PrewarmManager(
            fixture.constraints,
            fixture.pageSource,
            fixture.cache,
            fixture.enhancer,
            fixture.config,
            backgroundScope,
        )
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(2_000)
        runCurrent()
        manager.queueState.value.single().pauseReason shouldBe PrewarmPauseReason.READER_ACTIVE

        // The waiver only covers the charger requirement: the reader gate still holds.
        manager.prewarmNowOnce()
        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().let {
            it.status shouldBe PrewarmStatus.PAUSED
            it.pauseReason shouldBe PrewarmPauseReason.READER_ACTIVE
        }
        fixture.enhancer.enqueued shouldBe emptyList()

        // Leaving the reader lets the waived run continue even though nothing is plugged in.
        fixture.constraints.setReaderActive(false)
        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)
    }

    @Test
    fun `sufficient charge policy runs on battery above the threshold`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = false
        fixture.constraints.level = 60
        fixture.config.batteryPolicy = PrewarmBatteryPolicy.SUFFICIENT_CHARGE
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0, 1)
    }

    @Test
    fun `charge only policy waits even on a full battery when unplugged`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = false
        fixture.constraints.level = 95
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(2_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.PAUSED
        manager.queueState.value.single().pauseReason shouldBe PrewarmPauseReason.WAITING_CHARGE
        fixture.enhancer.enqueued shouldBe emptyList()
    }

    @Test
    fun `low battery pauses every policy and cannot be waived`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        fixture.constraints.charging = true
        fixture.constraints.level = 10
        fixture.config.batteryPolicy = PrewarmBatteryPolicy.ANY_TIME
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(2_000)
        runCurrent()
        manager.queueState.value.single().pauseReason shouldBe PrewarmPauseReason.LOW_BATTERY

        manager.prewarmNowOnce()
        advanceTimeBy(30_000)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.PAUSED
        manager.queueState.value.single().pauseReason shouldBe PrewarmPauseReason.LOW_BATTERY
        fixture.enhancer.enqueued shouldBe emptyList()
    }

    @Test
    fun `legacy charging switch maps onto the battery policy`() {
        ReaderPreferences.batteryPolicyForLegacyChargingRequirement(true) shouldBe
            ReaderPreferences.BATTERY_POLICY_CHARGE_ONLY
        ReaderPreferences.batteryPolicyForLegacyChargingRequirement(false) shouldBe
            ReaderPreferences.BATTERY_POLICY_ANY_TIME
    }

    @Test
    fun `enqueues tall page segments breadth first and counts logical pages`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2), segmentsPerPage = 2)
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(10_000)
        runCurrent()

        // Every page's first segment before any page's second one, so the reader's
        // "next page leading segment" preload hits an enhanced entry as early as possible.
        fixture.enhancer.enqueuedUnits shouldBe listOf(
            0 to "webtoon_seg_1",
            1 to "webtoon_seg_1",
            0 to "webtoon_seg_2",
            1 to "webtoon_seg_2",
        )
        // Progress stays in pages, not segments.
        val item = manager.queueState.value.single()
        item.status shouldBe PrewarmStatus.DONE
        item.totalPages shouldBe 2
        item.pagesCompleted shouldBe 2
        manager.stats.value.enhancedPages shouldBe 2
    }

    @Test
    fun `charges the inter-page cool-down per page instead of per segment`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2), segmentsPerPage = 3)
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        // Six units at 500ms each would need 3s; two pages need 1s.
        advanceTimeBy(1_200)
        runCurrent()
        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueuedUnits.size shouldBe 6
    }

    @Test
    fun `counts a page as completed only once all of its segments are cached`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 1), segmentsPerPage = 2)
        // The second segment already exists, so only the first one is dispatched.
        fixture.cache.cached += fixture.cache.key(1L, 10L, 0, "test-hash", ImageUtil.readerSegmentVariant(1))
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(10_000)
        runCurrent()

        fixture.enhancer.enqueuedUnits shouldBe listOf(0 to ImageUtil.readerSegmentVariant(0))
        val item = manager.queueState.value.single()
        item.status shouldBe PrewarmStatus.DONE
        item.totalPages shouldBe 1
        item.pagesCompleted shouldBe 1
        item.pagesCachedHits shouldBe 0
    }

    @Test
    fun `marks the chapter warmed once every page is enhanced`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(15_000)
        runCurrent()

        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        // The badge reads this marker after a restart, so a plain run has to write it.
        fixture.cache.wasMarkedWarmed(1L, 10L) shouldBe true
    }

    @Test
    fun `does not mark the chapter warmed when a unit was skipped`() = runTest {
        val fixture = Fixture(pageCounts = mapOf(10L to 2))
        // Page 1 is unprocessable for this configuration: the chapter completes, but one of its
        // pages has no enhanced image, so advertising it would be a false badge.
        fixture.cache.skipped += fixture.cache.key(1L, 10L, 1, "test-hash")
        val manager = manager(fixture)
        manager.queueChapters(manga, listOf(chapter(10L)))

        advanceTimeBy(15_000)
        runCurrent()

        manager.queueState.value.single().status shouldBe PrewarmStatus.DONE
        fixture.enhancer.enqueued shouldBe listOf(0)
        fixture.cache.wasMarkedWarmed(1L, 10L) shouldBe false
    }

    private fun TestScope.manager(fixture: Fixture) = PrewarmManager(
        fixture.constraints,
        fixture.pageSource,
        fixture.cache,
        fixture.enhancer,
        fixture.config,
        backgroundScope,
    )
}
