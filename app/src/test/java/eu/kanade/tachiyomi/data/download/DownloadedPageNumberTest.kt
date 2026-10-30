package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadedPageNumberTest {

    @Test
    fun `recognizes the names a chapter directory holds`() {
        downloadedPageNumber("017.webp") shouldBe 17
        // A provider that refused to overwrite left a second copy of the same page
        downloadedPageNumber("017 (1).webp") shouldBe 17
        // Segments of a split tall page belong to the page they were split from
        downloadedPageNumber("017__001.jpg") shouldBe 17
        downloadedPageNumber("017__002.jpg") shouldBe 17
    }

    @Test
    fun `ignores everything that is not a page image`() {
        downloadedPageNumber("ComicInfo.xml") shouldBe null
        downloadedPageNumber("meta.info") shouldBe null
        downloadedPageNumber(".nomedia") shouldBe null
        downloadedPageNumber("017.tmp") shouldBe null
        downloadedPageNumber("017 (1).tmp") shouldBe null
        downloadedPageNumber(null) shouldBe null
    }

    @Test
    fun `does not confuse a longer page number with a shorter one`() {
        downloadedPageNumber("0170.jpg") shouldBe 170
    }

    @Test
    fun `counts a chapter with duplicates and split pages as all of its pages`() {
        val names = listOf(
            "001.webp",
            "002.webp",
            "002 (1).webp",
            "003__001.jpg",
            "003__002.jpg",
            "ComicInfo.xml",
            ".nomedia",
        )

        names.mapNotNull(::downloadedPageNumber).toSet().size shouldBe 3
    }
}
