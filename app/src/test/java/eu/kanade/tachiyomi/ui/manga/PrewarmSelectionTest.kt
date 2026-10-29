package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.PrewarmAction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * Pins the prewarm selection rules (approach §1.5 stage A) so the download selectors and the
 * prewarm selectors can never drift back into each other.
 */
class PrewarmSelectionTest {

    private fun chapter(
        id: Long,
        read: Boolean = false,
        bookmark: Boolean = false,
    ): Chapter = Chapter.create().copy(
        id = id,
        mangaId = 1L,
        name = "C$id",
        url = "c$id",
        read = read,
        bookmark = bookmark,
    )

    /** 10 chapters in reading order, ids 1..10. */
    private fun chapters(count: Int = 10): List<Chapter> = (1..count).map { chapter(it.toLong()) }

    private fun List<Chapter>.ids(): List<Long> = map { it.id }

    @Test
    fun `next 5 keeps the window and counts the downloaded part`() {
        // Available chapters sit inside the window but not as a prefix: any implementation that
        // fills the window from the downloaded set would return 1, 3 and 5 only.
        val result = PrewarmSelection.select(
            action = PrewarmAction.NEXT_5_CHAPTERS,
            orderedChapters = chapters(),
            availableIds = setOf(1L, 3L, 5L),
        )

        result.total shouldBe 5
        result.downloaded shouldBe 3
        result.skipped shouldBe 2
        result.window.ids() shouldBe listOf(1L, 2L, 3L, 4L, 5L)
        result.available.ids() shouldBe listOf(1L, 3L, 5L)
    }

    @Test
    fun `next 5 never skips undownloaded chapters to fill the window`() {
        // Only chapters 6..8 are downloaded: the fixed window stays on 1..5 and is fully skipped.
        val result = PrewarmSelection.select(
            action = PrewarmAction.NEXT_5_CHAPTERS,
            orderedChapters = chapters(),
            availableIds = setOf(6L, 7L, 8L),
        )

        result.total shouldBe 5
        result.downloaded shouldBe 0
        result.window.ids() shouldBe listOf(1L, 2L, 3L, 4L, 5L)
        result.available shouldBe emptyList()
    }

    @Test
    fun `window sizes follow the action and clamp to the available chapters`() {
        val ordered = chapters(30)
        val allAvailable = ordered.mapTo(HashSet()) { it.id }

        PrewarmSelection.select(PrewarmAction.NEXT_1_CHAPTER, ordered, allAvailable).total shouldBe 1
        PrewarmSelection.select(PrewarmAction.NEXT_5_CHAPTERS, ordered, allAvailable).total shouldBe 5
        PrewarmSelection.select(PrewarmAction.NEXT_10_CHAPTERS, ordered, allAvailable).total shouldBe 10
        PrewarmSelection.select(PrewarmAction.NEXT_25_CHAPTERS, ordered, allAvailable).total shouldBe 25
        PrewarmSelection.select(PrewarmAction.UNREAD_CHAPTERS, ordered, allAvailable).total shouldBe 30

        // Fewer chapters than the window size: the window is what exists.
        val short = chapters(4)
        val shortAvailable = short.mapTo(HashSet()) { it.id }
        PrewarmSelection.select(PrewarmAction.NEXT_10_CHAPTERS, short, shortAvailable).total shouldBe 4
        PrewarmSelection.select(PrewarmAction.NEXT_25_CHAPTERS, short, shortAvailable).total shouldBe 4
    }

    @Test
    fun `unread drops the chapters that are already read`() {
        val ordered = listOf(
            chapter(1),
            chapter(2, read = true),
            chapter(3),
        )

        val result = PrewarmSelection.select(
            action = PrewarmAction.UNREAD_CHAPTERS,
            orderedChapters = ordered,
            availableIds = ordered.mapTo(HashSet()) { it.id },
        )

        result.total shouldBe 2
        result.window.ids() shouldBe listOf(1L, 3L)
    }

    @Test
    fun `bookmarked only keeps bookmarks and includes the read ones`() {
        val ordered = listOf(
            chapter(1, read = true, bookmark = true),
            chapter(2, bookmark = true),
            chapter(3),
        )

        val result = PrewarmSelection.select(
            action = PrewarmAction.BOOKMARKED_CHAPTERS,
            orderedChapters = ordered,
            availableIds = ordered.mapTo(HashSet()) { it.id },
        )

        result.total shouldBe 2
        result.window.ids() shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a local source has every chapter available`() {
        val ordered = chapters()
        val localAvailableIds = ordered.mapTo(HashSet()) { it.id }

        listOf(
            PrewarmAction.NEXT_1_CHAPTER,
            PrewarmAction.NEXT_5_CHAPTERS,
            PrewarmAction.NEXT_10_CHAPTERS,
            PrewarmAction.NEXT_25_CHAPTERS,
            PrewarmAction.UNREAD_CHAPTERS,
            PrewarmAction.BOOKMARKED_CHAPTERS,
        ).forEach { action ->
            val result = PrewarmSelection.select(action, ordered, localAvailableIds)
            result.downloaded shouldBe result.total
            result.skipped shouldBe 0
        }
    }

    @Test
    fun `available never reaches outside the window for any action`() {
        // The downloaded chapters live far past every fixed window, so this invariant is the one
        // that pins both halves of the decision: the window stays fixed in reading order, and the
        // available subset can only ever be a filter of it - never a refill from later chapters.
        val ordered = chapters(30)
        val downloadedLate = setOf(7L, 12L, 20L, 26L)

        PrewarmAction.entries.forEach { action ->
            val result = PrewarmSelection.select(action, ordered, downloadedLate)
            val windowIds = result.window.ids()

            result.available.ids() shouldBe windowIds.filter { it in downloadedLate }
            result.downloaded shouldBe result.available.size
            result.total shouldBe result.window.size
            result.skipped shouldBe result.total - result.downloaded
        }
    }

    @Test
    fun `fixed window actions take a contiguous prefix in reading order`() {
        val ordered = chapters(30)

        listOf(
            PrewarmAction.NEXT_1_CHAPTER to 1,
            PrewarmAction.NEXT_5_CHAPTERS to 5,
            PrewarmAction.NEXT_10_CHAPTERS to 10,
            PrewarmAction.NEXT_25_CHAPTERS to 25,
        ).forEach { (action, size) ->
            val window = PrewarmSelection.select(action, ordered, emptySet()).window
            window.ids() shouldBe ordered.map { it.id }.take(size)
        }
    }

    @Test
    fun `available is always a subset of the window for every action`() {
        // Mixed data: partially read, partially bookmarked, partially downloaded, so all six
        // actions take a non-trivial branch instead of degenerating to "everything" or "nothing".
        val ordered = (1..12).map { id ->
            chapter(
                id = id.toLong(),
                read = id % 3 == 0,
                bookmark = id % 4 == 0,
            )
        }
        val availableIds = setOf(1L, 2L, 3L, 8L, 12L)

        PrewarmAction.entries.forEach { action ->
            val result = PrewarmSelection.select(action, ordered, availableIds)
            // The available set comes from the window itself, never from chapters outside it.
            result.available.all { it in result.window } shouldBe true
            result.available.ids() shouldBe result.window.ids().filter { it in availableIds }
            result.downloaded shouldBe result.available.size
            result.total shouldBe result.window.size
            (result.downloaded <= result.total) shouldBe true
            result.skipped shouldBe result.total - result.downloaded
        }
    }

    @Test
    fun `no chapters means an empty window`() {
        PrewarmAction.entries.forEach { action ->
            val result = PrewarmSelection.select(action, emptyList(), emptySet())
            result.total shouldBe 0
            result.downloaded shouldBe 0
            result.isWindowEmpty shouldBe true
        }
        PrewarmCandidates.EMPTY.total shouldBe 0
        PrewarmCandidates.EMPTY.downloaded shouldBe 0
    }
}
