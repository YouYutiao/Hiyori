package eu.kanade.tachiyomi.data.prewarm

import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Pins the on-disk queue format. The file is the only thing a restart can rely on, so its shape is
 * a compatibility surface: names (not ordinals) for every enum, defaults for fields added later,
 * and finished chapters never written.
 */
class PrewarmQueueSnapshotTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `snapshot round-trips through the stored format`() {
        val snapshot = PrewarmQueueSnapshot(
            entries = listOf(
                PrewarmQueueEntry(
                    mangaId = 1L,
                    chapterId = 2L,
                    status = PrewarmStatus.ERROR.name,
                    pauseReason = PrewarmPauseReason.NONE.name,
                    skipReason = PrewarmSkipReason.CACHE_BUDGET_EXCEEDED.name,
                    queuedAt = 42L,
                    totalPages = 20,
                    pagesCompleted = 19,
                    pagesCachedHits = 3,
                    pagesFailed = 1,
                ),
            ),
            sort = PrewarmSort.CHAPTER_DESC.name,
            paused = true,
        )

        val decoded = json.decodeFromString<PrewarmQueueSnapshot>(json.encodeToString(snapshot))

        decoded shouldBe snapshot
    }

    @Test
    fun `enums travel by name so reordering them cannot remap an existing file`() {
        val snapshot = PrewarmQueueSnapshot(
            entries = listOf(PrewarmQueueEntry(mangaId = 1L, chapterId = 2L)),
            sort = PrewarmSort.CHAPTER_DESC.name,
        )

        val encoded = json.encodeToString(snapshot)

        encoded.contains(PrewarmSort.CHAPTER_DESC.name) shouldBe true
        encoded.contains(PrewarmStatus.QUEUED.name) shouldBe true
    }

    @Test
    fun `a snapshot written by an older build still decodes`() {
        // Only the identity was stored back then; every other field has to fall back to a default.
        val legacy = """{"entries":[{"mangaId":1,"chapterId":2}]}"""

        val snapshot = json.decodeFromString<PrewarmQueueSnapshot>(legacy)

        snapshot.entries.single().chapterId shouldBe 2L
        snapshot.entries.single().status shouldBe PrewarmStatus.QUEUED.name
        snapshot.entries.single().pagesCompleted shouldBe 0
        snapshot.sort shouldBe PrewarmSort.CUSTOM.name
        snapshot.paused shouldBe false
    }

    @Test
    fun `unknown sort names fall back to custom`() {
        PrewarmSort.fromStored("BY_VIBES") shouldBe PrewarmSort.CUSTOM
        PrewarmSort.fromStored(null) shouldBe PrewarmSort.CUSTOM
        PrewarmSort.fromStored(PrewarmSort.CHAPTER_ASC.name) shouldBe PrewarmSort.CHAPTER_ASC
    }

    @Test
    fun `flattening a queue row keeps identity and progress`() {
        val item = PrewarmItem(
            mangaId = 1L,
            mangaTitle = "Series",
            sourceId = 5L,
            sourceName = "Source",
            chapterId = 10L,
            chapterName = "Chapter 10",
            chapterNumber = 10.0,
            queuedAt = 3L,
            status = PrewarmStatus.ERROR,
            totalPages = 20,
            pagesCompleted = 18,
            pagesCachedHits = 2,
            pagesFailed = 2,
            skipReason = PrewarmSkipReason.CACHE_BUDGET_EXCEEDED,
        )

        item.toQueueEntry() shouldBe PrewarmQueueEntry(
            mangaId = 1L,
            chapterId = 10L,
            status = PrewarmStatus.ERROR.name,
            pauseReason = PrewarmPauseReason.NONE.name,
            skipReason = PrewarmSkipReason.CACHE_BUDGET_EXCEEDED.name,
            queuedAt = 3L,
            totalPages = 20,
            pagesCompleted = 18,
            pagesCachedHits = 2,
            pagesFailed = 2,
        )
    }
}
