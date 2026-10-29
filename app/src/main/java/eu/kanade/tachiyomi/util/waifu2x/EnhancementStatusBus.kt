package eu.kanade.tachiyomi.util.waifu2x

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.i18n.MR

/**
 * Enhancement state of a reader page, published by the page views and rendered by the reader
 * overlay (status badge and page-number marker). A closed state machine instead of free-form
 * labels: equal states never re-emit, progress is quantized, and there is no null state for a
 * bound page, so the overlay cannot flicker between show/hide or alternating texts.
 */
sealed interface PageEnhanceState {
    data object Enhanced : PageEnhanceState
    data object Raw : PageEnhanceState
    data class Processing(val phase: Phase, val progress: Int? = null) : PageEnhanceState {
        enum class Phase { QUEUED, ENHANCING, FINISHING }
    }
}

object EnhancementStatusBus {

    /**
     * Keyed by (chapterId, pageIndex): page indices repeat across chapters and adjacent
     * chapters are briefly attached at the same time during a transition, so a bare page
     * index would let two holders fight over one entry.
     */
    val states = MutableStateFlow<Map<Pair<Long, Int>, PageEnhanceState>>(emptyMap())

    /** Chapter of the most recent publish; drives [stateFor] lookups from the overlay. */
    private var currentChapterId: Long = -1L

    fun publish(chapterId: Long, pageIndex: Int, state: PageEnhanceState?) {
        currentChapterId = chapterId
        if (pageIndex < 0 || chapterId == -1L) return
        val key = chapterId to pageIndex
        states.value = states.value.toMutableMap().apply {
            when (state) {
                null -> remove(key)
                else -> if (get(key) != state) put(key, state)
            }
        }
        // Bound pages refresh their entries on selection; entries older than the current
        // chapter are dead weight once the transition finishes.
        if (states.value.size > 64) {
            states.value = states.value.filterKeys { it.first == chapterId }
        }
    }

    /** State of [pageIndex] within the chapter that most recently published. */
    fun stateFor(pageIndex: Int): PageEnhanceState? = states.value[currentChapterId to pageIndex]
}

/** Label resource and format argument for the overlay badge. */
fun PageEnhanceState.label(): Pair<StringResource, Int?> = when (this) {
    PageEnhanceState.Enhanced -> MR.strings.reader_status_processed to null
    PageEnhanceState.Raw -> MR.strings.reader_status_raw to null
    is PageEnhanceState.Processing -> when (phase) {
        PageEnhanceState.Processing.Phase.QUEUED -> MR.strings.reader_status_queued to null
        PageEnhanceState.Processing.Phase.ENHANCING -> MR.strings.reader_status_enhancing to progress
        PageEnhanceState.Processing.Phase.FINISHING -> MR.strings.reader_status_finishing to null
    }
}
