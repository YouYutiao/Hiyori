package eu.kanade.tachiyomi.util.waifu2x

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins how the 3GB trim attributes a deleted file back to its chapter. The trim drops the chapter's
 * completion marker for every chapter it touched - if this mapping silently returned the wrong
 * chapter (or a chapter for a stray file), the badge could keep claiming "ready" for a chapter whose
 * cached pages were just deleted.
 */
class PrewarmTrimInvalidationTest {

    private fun file(path: String): File = File(path.replace('/', File.separatorChar))

    @Test
    fun `maps a cached page to its manga and chapter`() {
        chapterKeyOf(file("cache/realcugan_cache/42/7001/3_abc123.webp")) shouldBe (42L to 7001L)
    }

    @Test
    fun `maps a completion marker to its chapter as well`() {
        chapterKeyOf(file("cache/realcugan_cache/42/7001/.warmed_abc123")) shouldBe (42L to 7001L)
    }

    @Test
    fun `ignores files that do not follow the layout`() {
        chapterKeyOf(file("cache/realcugan_cache/42/stray.webp")) shouldBe null
        chapterKeyOf(file("cache/realcugan_cache/not-a-manga/7001/3_abc.webp")) shouldBe null
        chapterKeyOf(file("cache/realcugan_cache/42/not-a-chapter/3_abc.webp")) shouldBe null
    }
}
