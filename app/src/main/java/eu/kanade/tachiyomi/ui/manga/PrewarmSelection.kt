package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.PrewarmAction
import tachiyomi.domain.chapter.model.Chapter

/**
 * Prewarm candidates for one menu action: the fixed reading-order window and its local subset.
 *
 * [window] is what the user asked for ("the next 5 chapters"), [available] is the part of it that
 * can actually be prewarmed right now. [skipped] is the difference the UI has to explain.
 */
internal data class PrewarmCandidates(
    val window: List<Chapter>,
    val available: List<Chapter>,
) {
    val total: Int get() = window.size
    val downloaded: Int get() = available.size
    val skipped: Int get() = total - downloaded
    val isWindowEmpty: Boolean get() = window.isEmpty()

    companion object {
        val EMPTY = PrewarmCandidates(emptyList(), emptyList())
    }
}

/**
 * Pure selection rules for the prewarm menu, kept free of ViewModel and Android dependencies so
 * the product decisions can be pinned by JVM unit tests.
 *
 * The prewarm selectors must not reuse the download ones (`MangaViewModel.getUnreadChapters()` and
 * friends), because those keep only *not yet downloaded* chapters - the exact opposite of what
 * prewarming needs.
 */
internal object PrewarmSelection {
    /**
     * Selects the prewarm candidates of [action].
     *
     * The window is sliced **before** it is filtered by [availableIds]: "next N chapters" means the
     * N chapters that come next in reading order, even when some (or all) of them are not
     * downloaded. Skipping missing chapters to fill the window would reach dozens of chapters
     * ahead, which is both unexpected and wasteful against the enhancement cache budget.
     *
     * @param orderedChapters all chapters in reading order (caller sorts).
     * @param availableIds chapters whose pages are already on disk (local source: every id).
     */
    fun select(
        action: PrewarmAction,
        orderedChapters: List<Chapter>,
        availableIds: Set<Long>,
    ): PrewarmCandidates {
        val eligible = orderedChapters.filter { chapter ->
            if (action == PrewarmAction.BOOKMARKED_CHAPTERS) chapter.bookmark else !chapter.read
        }
        val window = when (action) {
            PrewarmAction.NEXT_1_CHAPTER -> eligible.take(1)
            PrewarmAction.NEXT_5_CHAPTERS -> eligible.take(5)
            PrewarmAction.NEXT_10_CHAPTERS -> eligible.take(10)
            PrewarmAction.NEXT_25_CHAPTERS -> eligible.take(25)
            PrewarmAction.UNREAD_CHAPTERS, PrewarmAction.BOOKMARKED_CHAPTERS -> eligible
        }
        return PrewarmCandidates(window = window, available = window.filter { it.id in availableIds })
    }
}
