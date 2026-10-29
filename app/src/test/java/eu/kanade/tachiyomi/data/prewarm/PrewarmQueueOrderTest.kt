package eu.kanade.tachiyomi.data.prewarm

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the ordering rules of the prewarm queue: sorting only reorders chapters inside their own
 * series block, and dragging can never move a chapter into another series.
 *
 * The queue is always block-contiguous (the UI groups by series and every transform preserves
 * that), so the fixtures below keep one series after the other.
 */
class PrewarmQueueOrderTest {

    private fun item(
        mangaId: Long,
        chapterId: Long,
        number: Double,
        queuedAt: Long = chapterId,
    ) = PrewarmItem(
        mangaId = mangaId,
        mangaTitle = "M$mangaId",
        chapterId = chapterId,
        chapterName = "C$chapterId",
        chapterNumber = number,
        queuedAt = queuedAt,
    )

    /** Series A: 10, 20. Series B: 30, 40. */
    private fun twoBlocks() = listOf(
        item(1, 10, 1.0),
        item(1, 20, 2.0),
        item(2, 30, 1.0),
        item(2, 40, 2.0),
    )

    @Test
    fun `descending chapter order sorts inside every block and keeps blocks contiguous`() {
        val sorted = PrewarmQueueOrder.sorted(twoBlocks(), PrewarmSort.CHAPTER_DESC)
        sorted.map { it.chapterId } shouldBe listOf(20L, 10L, 40L, 30L)
        sorted.map { it.mangaId } shouldBe listOf(1L, 1L, 2L, 2L)
    }

    @Test
    fun `ascending chapter order restores the same order`() {
        val shuffled = listOf(
            item(1, 20, 2.0),
            item(1, 10, 1.0),
            item(2, 40, 4.0),
            item(2, 30, 3.0),
        )
        PrewarmQueueOrder.sorted(shuffled, PrewarmSort.CHAPTER_ASC).map { it.chapterId } shouldBe
            listOf(10L, 20L, 30L, 40L)
    }

    @Test
    fun `join order uses the queue stamp, not the id`() {
        val list = listOf(
            item(1, 10, 1.0, queuedAt = 30),
            item(1, 20, 2.0, queuedAt = 10),
            item(1, 30, 3.0, queuedAt = 20),
        )
        PrewarmQueueOrder.sorted(list, PrewarmSort.QUEUED).map { it.chapterId } shouldBe
            listOf(20L, 30L, 10L)
    }

    @Test
    fun `custom sort leaves the list untouched`() {
        val list = twoBlocks()
        PrewarmQueueOrder.sorted(list, PrewarmSort.CUSTOM) shouldBe list
    }

    @Test
    fun `dragging a chapter past its block edge clamps to the end of that block`() {
        // 99 would land inside series B; the chapter must stay in series A.
        val moved = PrewarmQueueOrder.moveChapter(twoBlocks(), chapterId = 10, toIndexInSeries = 99)
        moved.map { it.chapterId } shouldBe listOf(20L, 10L, 30L, 40L)
        moved.map { it.mangaId } shouldBe listOf(1L, 1L, 2L, 2L)
    }

    @Test
    fun `dragging a chapter to the top of its block clamps at zero`() {
        val moved = PrewarmQueueOrder.moveChapter(twoBlocks(), chapterId = 20, toIndexInSeries = -5)
        moved.map { it.chapterId } shouldBe listOf(20L, 10L, 30L, 40L)
        moved.map { it.mangaId } shouldBe listOf(1L, 1L, 2L, 2L)
    }

    @Test
    fun `moving the last chapter of a block to its top keeps the block in place`() {
        val moved = PrewarmQueueOrder.moveChapter(twoBlocks(), chapterId = 40, toIndexInSeries = 0)
        moved.map { it.chapterId } shouldBe listOf(10L, 20L, 40L, 30L)
        moved.map { it.mangaId } shouldBe listOf(1L, 1L, 2L, 2L)
    }

    @Test
    fun `moving a whole series reorders the blocks`() {
        val moved = PrewarmQueueOrder.moveSeries(twoBlocks(), mangaId = 2, toIndex = 0)
        moved.map { it.chapterId } shouldBe listOf(30L, 40L, 10L, 20L)
        moved.map { it.mangaId } shouldBe listOf(2L, 2L, 1L, 1L)
    }

    @Test
    fun `unknown ids leave the queue untouched`() {
        val list = twoBlocks()
        PrewarmQueueOrder.moveChapter(list, chapterId = 999, toIndexInSeries = 0) shouldBe list
        PrewarmQueueOrder.moveSeries(list, mangaId = 999, toIndex = 0) shouldBe list
    }

    @Test
    fun `series order follows first appearance`() {
        PrewarmQueueOrder.seriesOrder(twoBlocks()) shouldBe listOf(1L, 2L)
    }
}
