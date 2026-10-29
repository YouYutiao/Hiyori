package eu.kanade.presentation.manga

enum class DownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
    BOOKMARKED_CHAPTERS,
}

enum class PrewarmAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
    BOOKMARKED_CHAPTERS,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
    ADD_TO_LIBRARY,
}

/**
 * How many chapters a prewarm action would cover and how many of them are already downloaded.
 * Only downloaded chapters can be prewarmed, so [downloaded] decides whether the menu item is
 * actionable.
 */
data class PrewarmMenuCount(
    val total: Int,
    val downloaded: Int,
) {
    val enabled: Boolean get() = downloaded > 0
}

enum class MangaScreenItem {
    INFO_BOX,
    ACTION_ROW,
    DESCRIPTION_WITH_TAG,
    CHAPTER_HEADER,
    CHAPTER,
}
