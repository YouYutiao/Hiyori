package eu.kanade.tachiyomi.util.waifu2x

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the "this chapter is warmed" rules. The indicator is the only place the user learns that a
 * chapter is ready, so a false positive (badging a chapter that still shows original pages) is
 * worse than a false negative - every test below that ends in "not warmed" is that guarantee.
 */
class PrewarmCacheIndexTest {

    /** A realistic hash: config hashes embed separators, which is why the whole name is matched. */
    private val configHash = "2x2_m0_rs0_w0_h0_sw0_sh0_t128_p0_fa0_b0_dv1_nv3_ink0"

    private val otherConfigHash = "2x1_m4_rs0_w0_h0_sw0_sh0_t128_p0_fa0_b0_dv1_nv3_ink0"

    /** Chapter id -> names present in that chapter's cache directory. */
    private fun warmed(dirs: Map<Long, List<String>>, hash: String = configHash): Set<Long> {
        val markerName = PrewarmCacheIndex.warmedMarkerName(hash)
        return PrewarmCacheIndex.warmedChapterIds(
            configHash = hash,
            candidates = dirs.keys,
            hasMarker = { chapterId -> markerName in dirs.getValue(chapterId) },
        )
    }

    @Test
    fun `the marker name carries the configuration hash`() {
        PrewarmCacheIndex.warmedMarkerName(configHash) shouldBe ".warmed_$configHash"
        // Hidden file inside the chapter directory, so no variant or page entry can look like it.
        PrewarmCacheIndex.warmedMarkerName(configHash).startsWith(".") shouldBe true
    }

    @Test
    fun `only a marker name counts as a marker`() {
        // Cache trims delete page entries by name and must leave the promise untouched.
        PrewarmCacheIndex.isWarmedMarker(PrewarmCacheIndex.warmedMarkerName(configHash)) shouldBe true
        PrewarmCacheIndex.isWarmedMarker("0_$configHash.webp") shouldBe false
        PrewarmCacheIndex.isWarmedMarker("0_${configHash}_webtoon_seg_1.skip") shouldBe false
    }

    @Test
    fun `a chapter with a marker for the current configuration is warmed`() {
        warmed(
            mapOf(
                1L to listOf(PrewarmCacheIndex.warmedMarkerName(configHash)),
                2L to listOf(PrewarmCacheIndex.warmedMarkerName(configHash)),
            ),
        ) shouldBe setOf(1L, 2L)
    }

    @Test
    fun `a chapter without a marker is never warmed`() {
        // Enhanced images alone prove nothing: this is the case that used to badge a chapter after
        // the first pass over a segmented chapter, while most pages were still originals.
        warmed(
            mapOf(
                1L to listOf("0_$configHash.webp", "1_${configHash}_webtoon_seg_1.webp"),
                2L to emptyList(),
            ),
        ) shouldBe emptySet()
    }

    @Test
    fun `a marker written for another configuration does not count`() {
        warmed(
            mapOf(1L to listOf(PrewarmCacheIndex.warmedMarkerName(otherConfigHash))),
        ) shouldBe emptySet()

        // Mixed names only ever contribute the marker of the configuration in use.
        warmed(
            mapOf(
                1L to listOf(
                    PrewarmCacheIndex.warmedMarkerName(otherConfigHash),
                    PrewarmCacheIndex.warmedMarkerName(configHash),
                ),
            ),
        ) shouldBe setOf(1L)
    }

    @Test
    fun `a hash that shares a prefix with ours is not our marker`() {
        val longerHash = configHash + "_extra"
        warmed(
            mapOf(1L to listOf(PrewarmCacheIndex.warmedMarkerName(longerHash))),
        ) shouldBe emptySet()
    }

    @Test
    fun `chapters are judged independently`() {
        warmed(
            mapOf(
                1L to listOf(PrewarmCacheIndex.warmedMarkerName(configHash)),
                2L to listOf("0_$configHash.webp"),
                3L to listOf(PrewarmCacheIndex.warmedMarkerName(configHash)),
            ),
        ) shouldBe setOf(1L, 3L)
    }

    @Test
    fun `an empty configuration or no candidates is never warmed`() {
        warmed(mapOf(1L to listOf(".warmed_")), hash = "") shouldBe emptySet()
        PrewarmCacheIndex.warmedChapterIds("", setOf(1L)) { true } shouldBe emptySet()
        PrewarmCacheIndex.warmedChapterIds(configHash, emptySet()) { true } shouldBe emptySet()
    }

    @Test
    fun `a complete run without skips or failures writes the marker`() {
        PrewarmCacheIndex.shouldMarkWarmed(
            totalPages = 20,
            completedPages = 20,
            skippedUnits = 0,
            failedUnits = 0,
        ) shouldBe true
    }

    @Test
    fun `a skipped unit blocks the marker`() {
        // A skipped unit is a page or segment the enhancer refused: it has no enhanced image, so
        // the chapter would still show an original there.
        PrewarmCacheIndex.shouldMarkWarmed(
            totalPages = 20,
            completedPages = 20,
            skippedUnits = 1,
            failedUnits = 0,
        ) shouldBe false
    }

    @Test
    fun `a failed unit blocks the marker`() {
        PrewarmCacheIndex.shouldMarkWarmed(
            totalPages = 20,
            completedPages = 19,
            skippedUnits = 0,
            failedUnits = 1,
        ) shouldBe false
    }

    @Test
    fun `an unfinished or unknown chapter is never marked`() {
        // Cancelled mid-run: not every page was reached.
        PrewarmCacheIndex.shouldMarkWarmed(
            totalPages = 20,
            completedPages = 12,
            skippedUnits = 0,
            failedUnits = 0,
        ) shouldBe false
        // Could not be enumerated at all.
        PrewarmCacheIndex.shouldMarkWarmed(
            totalPages = 0,
            completedPages = 0,
            skippedUnits = 0,
            failedUnits = 0,
        ) shouldBe false
    }
}
