package eu.kanade.tachiyomi.util.waifu2x

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.system.ImageUtil
import kotlin.math.ceil

/**
 * Locks the segmentation contract the webtoon reader and the prewarm queue share for a tall page.
 *
 * The pixels cannot be cut here - `BitmapRegionDecoder` and `Bitmap.compress` are Android framework
 * stubs in a plain JVM test - so these tests pin the two properties that make a prewarmed segment
 * indistinguishable from the displayed one:
 *
 * 1. both sides are cut by the same deterministic plan ([ImageUtil.readerSegmentPlan]), which
 *    covers the page exactly once, so identical input bytes always produce identical segments;
 * 2. both sides derive the cache key from the same function ([ImageUtil.readerSegmentVariant]), so
 *    the entry prewarm writes is the entry the reader looks up.
 */
class ReaderSegmentsTest {

    /** Reader threshold modelled by these tests: a 1920px-tall screen, doubled. */
    private val displayMaxImageHeight = 3840

    @Test
    fun `plan splits a tall strip into ceil of height over segment height parts`() {
        val plan = ImageUtil.readerSegmentPlan(800, 6000, displayMaxImageHeight)!!
        plan.size shouldBe ceil(6000.0 / ImageUtil.READER_SEGMENT_MAX_HEIGHT).toInt()
        plan.size shouldBe 3

        // Heights that are not a multiple of the segment budget round the same way the reader
        // always cut them, because both sides go through the same planner.
        ImageUtil.readerSegmentPlan(800, 6001, displayMaxImageHeight)!!.size shouldBe
            ceil(6001.0 / ImageUtil.READER_SEGMENT_MAX_HEIGHT).toInt()
        ImageUtil.readerSegmentPlan(800, 4000, displayMaxImageHeight)!!.size shouldBe 2
    }

    @Test
    fun `plan covers the page exactly once so segment bytes are reproducible`() {
        for (height in listOf(4001, 6000, 6001, 12_345, 30_000)) {
            val plan = ImageUtil.readerSegmentPlan(800, height, displayMaxImageHeight)!!
            plan.first().topOffset shouldBe 0
            plan.last().bottomOffset shouldBe height
            plan.forEachIndexed { index, split ->
                split.index shouldBe index
                split.splitWidth shouldBe 800
                if (index > 0) split.topOffset shouldBe plan[index - 1].bottomOffset
            }
            plan.sumOf { it.splitHeight } shouldBe height
        }
    }

    @Test
    fun `image that is not tall enough stays whole`() {
        // 2999 is not taller than 1000 * 3, so the reader never splits it.
        ImageUtil.readerSegmentPlan(1000, 2999, displayMaxImageHeight).shouldBeNull()
    }

    @Test
    fun `tall image that fits the reader budget stays whole`() {
        // Tall (2000 > 3 * 400) but fits one segment.
        ImageUtil.readerSegmentPlan(400, 2000, 13_840).shouldBeNull()
        // Tall, but not taller than the display budget the reader uses for the split decision.
        ImageUtil.readerSegmentPlan(800, 3000, displayMaxImageHeight).shouldBeNull()
    }

    @Test
    fun `degenerate dimensions never produce a plan`() {
        ImageUtil.readerSegmentPlan(0, 6000, displayMaxImageHeight).shouldBeNull()
        ImageUtil.readerSegmentPlan(800, 0, displayMaxImageHeight).shouldBeNull()
        ImageUtil.readerSegmentPlan(-1, -1, displayMaxImageHeight).shouldBeNull()
    }

    @Test
    fun `segment variants are one based and identical on both sides`() {
        // The literal format is the on-disk contract of the webtoon_seg_N cache files.
        val expected = listOf("webtoon_seg_1", "webtoon_seg_2", "webtoon_seg_3")

        // Reader side (WebtoonPageHolder) passes the page's key suffix; prewarm uses the bare form.
        val readerSide = List(3) { ImageUtil.readerSegmentVariant("", it) }
        val prewarmSide = List(3) { ImageUtil.readerSegmentVariant(it) }

        readerSide shouldContainExactly expected
        prewarmSide shouldContainExactly expected
        prewarmSide shouldBe readerSide
    }

    @Test
    fun `variant count matches the plan size for the same page`() {
        val plan = ImageUtil.readerSegmentPlan(800, 6000, displayMaxImageHeight)!!
        val readerVariants = List(plan.size) { ImageUtil.readerSegmentVariant("", it) }
        val prewarmVariants = List(plan.size) { ImageUtil.readerSegmentVariant(it) }
        readerVariants shouldContainExactly listOf("webtoon_seg_1", "webtoon_seg_2", "webtoon_seg_3")
        prewarmVariants shouldBe readerVariants
    }

    @Test
    fun `prefixed base variant still shares the segment part`() {
        // A split spread half keeps its own namespace, and the segment suffix stays the shared one.
        ImageUtil.readerSegmentVariant("wide_left", 0) shouldBe "wide_left__webtoon_seg_1"
        ImageUtil.readerSegmentVariant("wide_left", 0).substringAfterLast("__") shouldBe
            ImageUtil.readerSegmentVariant(0)
    }
}
