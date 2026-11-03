package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PredecodeWindowTest {

    private val screenHeight = 2000

    @Test
    fun `visible segment is always inside the window`() {
        assertTrue(isWithinPredecodeWindow(top = 0, height = 2000, screenHeight = screenHeight))
    }

    @Test
    fun `segment two screens ahead is still decoded`() {
        val top = screenHeight * 2 + 1
        assertTrue(isWithinPredecodeWindow(top = top, height = 2000, screenHeight = screenHeight))
    }

    @Test
    fun `segment three screens ahead is left alone`() {
        val top = screenHeight * 3
        assertFalse(isWithinPredecodeWindow(top = top, height = 2000, screenHeight = screenHeight))
    }

    @Test
    fun `segment one screen behind is kept`() {
        // Bottom edge just inside the one-screen lookback.
        val top = -screenHeight - 1000
        assertTrue(isWithinPredecodeWindow(top = top, height = 2000, screenHeight = screenHeight))
    }

    @Test
    fun `segment further behind is dropped`() {
        val top = -screenHeight - 2000
        assertFalse(isWithinPredecodeWindow(top = top, height = 2000, screenHeight = screenHeight))
    }
}
