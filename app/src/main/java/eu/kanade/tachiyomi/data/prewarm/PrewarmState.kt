package eu.kanade.tachiyomi.data.prewarm

/** Lifecycle of a chapter inside the prewarm queue. */
enum class PrewarmStatus {
    /** Waiting for its turn. */
    QUEUED,

    /** Pages are being enhanced one by one. */
    RUNNING,

    /** Gated by constraints (charging / reader activity / switches) or manually paused. */
    PAUSED,

    /** Every page is cached as of the last run. */
    DONE,

    /** Not prewarmable at all (missing files, auto page split, cache budget). */
    SKIPPED,

    /** The run finished but at least one page failed to enhance. */
    ERROR,
}

/** Why the queue is not making progress right now. */
enum class PrewarmPauseReason {
    NONE,
    MANUAL,
    WAITING_CHARGE,
    LOW_BATTERY,
    READER_ACTIVE,
    PREWARM_DISABLED,
    ENHANCEMENT_DISABLED,
}

/** Why a chapter was skipped entirely instead of being enhanced. */
enum class PrewarmSkipReason {
    CACHE_BUDGET_EXCEEDED,
    CHAPTER_UNAVAILABLE,
    AUTO_SPLIT_UNSUPPORTED,
}

data class PrewarmItem(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    /** Chapter number used by the "sort by chapter number" queue action. */
    val chapterNumber: Double = 0.0,
    /** Monotonic stamp used by the "join order" queue action. */
    val queuedAt: Long = 0L,
    val status: PrewarmStatus = PrewarmStatus.QUEUED,
    /** Total pages found in the chapter; 0 until the chapter was enumerated. */
    val totalPages: Int = 0,
    /** Pages already usable from cache (hits found before the run + pages enhanced by this run). */
    val pagesCompleted: Int = 0,
    /** Pages that were already cached when the chapter started. */
    val pagesCachedHits: Int = 0,
    val pagesFailed: Int = 0,
    val pauseReason: PrewarmPauseReason = PrewarmPauseReason.NONE,
    val skipReason: PrewarmSkipReason? = null,
)

/**
 * How the chapters inside each series block are ordered.
 *
 * [CUSTOM] means "whatever the user arranged by dragging"; picking any other mode only reorders the
 * chapters inside their blocks, never the blocks themselves.
 */
enum class PrewarmSort {
    CUSTOM,
    QUEUED,
    CHAPTER_ASC,
    CHAPTER_DESC,
}

/** Statuses that still represent pending work in this process. */
val PrewarmStatus.isActive: Boolean
    get() = this == PrewarmStatus.QUEUED || this == PrewarmStatus.RUNNING || this == PrewarmStatus.PAUSED

/** Cumulative counters of the current process prewarm session, for the queue footer. */
data class PrewarmSessionStats(
    val enhancedPages: Int = 0,
    val cacheHitPages: Int = 0,
    val failedPages: Int = 0,
)

/** What the last finished run accomplished, surfaced after the queue goes idle. */
data class PrewarmCompletionSummary(
    val chapterCount: Int,
    val pageCount: Int,
    val finishedAt: Long,
)
