package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The preemption budget decides whether work already done is thrown away. It used to be spent on
 * every page change, which discarded about 83% of the accelerator's work while scrolling.
 */
class PreemptKeepWindowTest {

    @Test
    fun `the page right ahead is finished`() {
        assertTrue(isWithinPreemptKeepWindow(activeIndex = 7, visibleIndex = 6))
    }

    @Test
    fun `the page two ahead is still finished`() {
        assertTrue(isWithinPreemptKeepWindow(activeIndex = 8, visibleIndex = 6))
    }

    @Test
    fun `a page three ahead is given up`() {
        assertFalse(isWithinPreemptKeepWindow(activeIndex = 9, visibleIndex = 6))
    }

    @Test
    fun `a page behind the visible one is given up immediately`() {
        // Already read: finishing it would delay the page on screen.
        assertFalse(isWithinPreemptKeepWindow(activeIndex = 5, visibleIndex = 6))
        assertFalse(isWithinPreemptKeepWindow(activeIndex = 4, visibleIndex = 6))
    }

    @Test
    fun `the visible page itself is not kept`() {
        assertFalse(isWithinPreemptKeepWindow(activeIndex = 6, visibleIndex = 6))
    }
}
