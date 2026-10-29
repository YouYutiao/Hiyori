package eu.kanade.tachiyomi.util.waifu2x

/**
 * Rules for the "this chapter is fully warmed" marker.
 *
 * Kept apart from [ImageEnhancementCache] so the rules can be unit tested without a filesystem:
 * every function here is a pure transformation of a configuration hash, a chapter id set and a
 * marker probe.
 *
 * A chapter is warmed only when a prewarm run finished it and left every page usable from the
 * cache. That fact is recorded explicitly, as a zero-byte `.warmed_<configHash>` file inside the
 * chapter's cache directory, instead of being inferred from the enhanced images that happen to be
 * on disk: one tall page keeps many segment entries and a page keeps one entry per render variant,
 * so "enough files exist" never proved that every page was done - it badged a chapter after the
 * first pass over a segmented chapter, while most of its pages were still originals.
 *
 * The marker sits inside the chapter directory, so deleting the chapter's cache
 * (`clearChapterCache`) or the whole cache (`clear`) removes it with no extra bookkeeping, and its
 * name carries the config hash, so changed enhancement settings simply stop matching it. Upgrading
 * from a build that warmed chapters without marking them therefore hides those badges until the
 * chapter is prewarmed once more: a missing badge is the accepted trade for never showing a false
 * one.
 */
internal object PrewarmCacheIndex {

    /** Prefix of the completion marker; the rest of the name is the configuration hash. */
    private const val MARKER_PREFIX = ".warmed_"

    /** Name of the completion marker for [configHash], inside a chapter's cache directory. */
    fun warmedMarkerName(configHash: String): String = MARKER_PREFIX + configHash

    /**
     * Whether [fileName] is a completion marker, for callers that delete cache entries by name and
     * must notice when they removed the promise that a chapter is warmed.
     */
    fun isWarmedMarker(fileName: String): Boolean = fileName.startsWith(MARKER_PREFIX)

    /**
     * Chapters of [candidates] that carry a marker for [configHash].
     *
     * [hasMarker] answers "does this chapter directory hold the marker"; it is a probe rather than
     * a listing so the caller decides how much filesystem work a scan costs.
     *
     * An empty [configHash] never matches anything: without a configuration there is nothing to be
     * warmed for.
     */
    fun warmedChapterIds(
        configHash: String,
        candidates: Set<Long>,
        hasMarker: (Long) -> Boolean,
    ): Set<Long> {
        if (configHash.isEmpty() || candidates.isEmpty()) return emptySet()
        return candidates.filterTo(HashSet(candidates.size), hasMarker)
    }

    /**
     * Whether a finished run may write the marker.
     *
     * [totalPages] is the chapter's logical page count (0 means the chapter could not be
     * enumerated, which is never warmed) and [completedPages] the pages whose every unit is
     * settled. [skippedUnits] and [failedUnits] are the units that did not end up enhanced: a
     * skipped unit is a page or segment the enhancer refused, so the chapter would still show an
     * original there, and a failed unit means an entry is missing. Both block the marker - the
     * badge is a promise, and a missing badge is a far smaller lie than a false one.
     */
    fun shouldMarkWarmed(
        totalPages: Int,
        completedPages: Int,
        skippedUnits: Int,
        failedUnits: Int,
    ): Boolean {
        return totalPages > 0 && completedPages >= totalPages && skippedUnits == 0 && failedUnits == 0
    }
}
