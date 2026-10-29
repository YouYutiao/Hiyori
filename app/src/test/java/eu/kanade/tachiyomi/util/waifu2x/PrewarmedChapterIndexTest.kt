package eu.kanade.tachiyomi.util.waifu2x

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pins the in-process warmed-chapter index.
 *
 * Two properties matter and both are tested below: an unknown answer must stay unknown (a screen
 * reading it would otherwise badge nothing at all on the first frame), and a known answer must be
 * updated by the same events that change the marker on disk, so the badge appears and disappears
 * without waiting for a directory walk.
 */
class PrewarmedChapterIndexTest {

    /** A realistic hash: config hashes embed separators, which is why the whole name is matched. */
    private val configHash = "2x2_m0_rs0_w0_h0_sw0_sh0_t128_p0_fa0_b0_dv1_nv3_ink0"

    private val otherConfigHash = "2x1_m4_rs0_w0_h0_sw0_sh0_t128_p0_fa0_b0_dv1_nv3_ink0"

    @BeforeEach
    fun clearIndex() {
        // The index is a process singleton, so tests must not inherit each other's entries.
        PrewarmedChapterIndex.clear()
    }

    @Test
    fun `an empty index answers unknown instead of cold`() {
        PrewarmedChapterIndex.get(1L, configHash).shouldBeNull()
        PrewarmedChapterIndex.updates.value.shouldBeNull()
    }

    @Test
    fun `a scan result is readable without suspending`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L, 11L))

        PrewarmedChapterIndex.get(1L, configHash) shouldBe setOf(10L, 11L)
        // A manga that was never recorded is unknown, never "nothing warmed".
        PrewarmedChapterIndex.get(2L, configHash).shouldBeNull()
    }

    @Test
    fun `a finished chapter joins the set without a new scan`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L))

        PrewarmedChapterIndex.markWarmed(mangaId = 1L, chapterId = 11L, configHash = configHash)

        PrewarmedChapterIndex.get(1L, configHash) shouldBe setOf(10L, 11L)
        // This is what the details screen follows, so it must carry the new chapter too.
        PrewarmedChapterIndex.updates.value?.byManga?.get(1L) shouldBe setOf(10L, 11L)
    }

    @Test
    fun `a chapter warmed before the first scan is already reported`() {
        PrewarmedChapterIndex.markWarmed(mangaId = 1L, chapterId = 11L, configHash = configHash)

        // One chapter is true information; the scan that follows replaces it with the full set.
        PrewarmedChapterIndex.get(1L, configHash) shouldBe setOf(11L)
    }

    @Test
    fun `a changed configuration invalidates every entry`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L))

        PrewarmedChapterIndex.get(1L, otherConfigHash).shouldBeNull()

        PrewarmedChapterIndex.markWarmed(mangaId = 1L, chapterId = 20L, configHash = otherConfigHash)

        PrewarmedChapterIndex.get(1L, otherConfigHash) shouldBe setOf(20L)
        // The markers of the previous configuration are meaningless now.
        PrewarmedChapterIndex.get(1L, configHash).shouldBeNull()
    }

    @Test
    fun `a chapter whose cache was deleted stops being warmed`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L, 11L))

        PrewarmedChapterIndex.removeChapter(1L, 10L)

        PrewarmedChapterIndex.get(1L, configHash) shouldBe setOf(11L)
    }

    @Test
    fun `removing the last chapter forgets the manga`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L))

        PrewarmedChapterIndex.removeChapter(1L, 10L)

        // Unknown again, so the next lookup asks the filesystem instead of trusting an empty set.
        PrewarmedChapterIndex.get(1L, configHash).shouldBeNull()
    }

    @Test
    fun `removing an unknown chapter leaves the manga unknown`() {
        PrewarmedChapterIndex.removeChapter(1L, 10L)

        PrewarmedChapterIndex.get(1L, configHash).shouldBeNull()
    }

    @Test
    fun `wiping the cache forgets everything`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L))
        PrewarmedChapterIndex.put(2L, configHash, setOf(20L))

        PrewarmedChapterIndex.clear()

        PrewarmedChapterIndex.get(1L, configHash).shouldBeNull()
        PrewarmedChapterIndex.get(2L, configHash).shouldBeNull()
        PrewarmedChapterIndex.updates.value.shouldBeNull()
    }

    @Test
    fun `one manga never loses another manga's chapters`() {
        PrewarmedChapterIndex.put(1L, configHash, setOf(10L))
        PrewarmedChapterIndex.put(2L, configHash, setOf(20L))
        PrewarmedChapterIndex.markWarmed(mangaId = 1L, chapterId = 11L, configHash = configHash)

        PrewarmedChapterIndex.get(1L, configHash) shouldBe setOf(10L, 11L)
        PrewarmedChapterIndex.get(2L, configHash) shouldBe setOf(20L)
    }
}
