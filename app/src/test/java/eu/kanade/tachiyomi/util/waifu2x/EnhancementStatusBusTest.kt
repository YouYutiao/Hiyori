package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The overlay reads the badge through a collected snapshot. Reading the flow directly inside a
 * composable is invisible to Compose, which is exactly how a page that finished enhancing kept
 * showing "processing" until something unrelated forced a recomposition.
 */
class EnhancementStatusBusTest {

    private fun processing() = PageEnhanceState.Processing(PageEnhanceState.Processing.Phase.ENHANCING)

    @Test
    fun `a published state is readable from the snapshot`() {
        EnhancementStatusBus.publish(101L, 3, processing())
        assertEquals(processing(), EnhancementStatusBus.stateFor(3, EnhancementStatusBus.states.value))
    }

    @Test
    fun `a finished page is readable from the same snapshot`() {
        EnhancementStatusBus.publish(102L, 3, processing())
        EnhancementStatusBus.publish(102L, 3, PageEnhanceState.Enhanced)
        assertEquals(PageEnhanceState.Enhanced, EnhancementStatusBus.stateFor(3, EnhancementStatusBus.states.value))
    }

    @Test
    fun `another page of the same chapter stays empty`() {
        EnhancementStatusBus.publish(103L, 3, PageEnhanceState.Enhanced)
        assertNull(EnhancementStatusBus.stateFor(4, EnhancementStatusBus.states.value))
    }

    @Test
    fun `pages from a previous chapter are not reachable`() {
        EnhancementStatusBus.publish(104L, 7, PageEnhanceState.Enhanced)
        EnhancementStatusBus.publish(105L, 1, PageEnhanceState.Enhanced)
        // Same page number, different chapter: the snapshot is keyed by both.
        assertNull(EnhancementStatusBus.stateFor(7, EnhancementStatusBus.states.value))
    }

    @Test
    fun `republishing an equal state does not replace the map`() {
        EnhancementStatusBus.publish(106L, 2, PageEnhanceState.Raw)
        val before = EnhancementStatusBus.states.value
        EnhancementStatusBus.publish(106L, 2, PageEnhanceState.Raw)
        assertSame(before, EnhancementStatusBus.states.value)
    }
}
