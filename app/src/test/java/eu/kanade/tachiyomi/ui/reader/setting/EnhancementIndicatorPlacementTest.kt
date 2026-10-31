package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the multi-select placement rules: the two corners exclude each other, the page number marker
 * stacks on either, and the last remaining placement cannot be switched off.
 */
class EnhancementIndicatorPlacementTest {

    private val left = ReaderPreferences.INDICATOR_PLACEMENT_LEFT
    private val right = ReaderPreferences.INDICATOR_PLACEMENT_RIGHT
    private val page = ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER

    @Test
    fun `a single corner draws only that badge`() {
        ReaderPreferences.drawsLeftBadge(left) shouldBe true
        ReaderPreferences.drawsRightBadge(left) shouldBe false
        ReaderPreferences.marksPageNumber(left) shouldBe false
        ReaderPreferences.drawsBadge(left) shouldBe true

        ReaderPreferences.drawsLeftBadge(right) shouldBe false
        ReaderPreferences.drawsRightBadge(right) shouldBe true
        ReaderPreferences.marksPageNumber(right) shouldBe false
        ReaderPreferences.drawsBadge(right) shouldBe true
    }

    @Test
    fun `the marker stacks on a corner`() {
        val both = left or page
        ReaderPreferences.drawsLeftBadge(both) shouldBe true
        ReaderPreferences.drawsRightBadge(both) shouldBe false
        ReaderPreferences.marksPageNumber(both) shouldBe true
        ReaderPreferences.drawsBadge(both) shouldBe true
    }

    @Test
    fun `the marker alone draws no badge`() {
        ReaderPreferences.drawsLeftBadge(page) shouldBe false
        ReaderPreferences.drawsRightBadge(page) shouldBe false
        ReaderPreferences.marksPageNumber(page) shouldBe true
        ReaderPreferences.drawsBadge(page) shouldBe false
    }

    @Test
    fun `adding the marker keeps the corner`() {
        ReaderPreferences.togglePlacement(left, page) shouldBe (left or page)
    }

    @Test
    fun `picking the other corner drops the first but keeps the marker`() {
        ReaderPreferences.togglePlacement(left or page, right) shouldBe (right or page)
        ReaderPreferences.togglePlacement(right or page, left) shouldBe (left or page)
    }

    @Test
    fun `tapping the selected corner clears it while the marker remains`() {
        ReaderPreferences.togglePlacement(left or page, left) shouldBe page
    }

    @Test
    fun `the last remaining placement cannot be cleared`() {
        ReaderPreferences.togglePlacement(left, left) shouldBe left
        ReaderPreferences.togglePlacement(page, page) shouldBe page
    }
}
