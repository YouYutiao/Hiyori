package eu.kanade.tachiyomi.util.waifu2x

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process answer to "which chapters of this manga are already warmed".
 *
 * The badge used to become visible only after the details screen had loaded, waited for the
 * chapter list, debounced a scan signal and finally walked the cache directory. That is a lot of
 * latency for a fact the process usually learns much earlier: a prewarm run finishes a chapter and
 * writes its marker, so the very next frame may already show the badge.
 *
 * This index is that early knowledge, kept per configuration hash because a marker is only valid
 * for the settings it was written with. It is a cache of the filesystem state, never a source of
 * truth: the disk scan still runs and overwrites whatever is here, so a lost or stale entry can
 * only ever cost a missing badge for a moment, never a false one.
 *
 * Entries are written from three places, which together cover every way a chapter's marker can
 * appear or disappear: [ImageEnhancementCache.markChapterWarmed] (a run finished a chapter),
 * [ImageEnhancementCache.clearChapterCache] (a chapter's cache was deleted) and
 * [ImageEnhancementCache.clear] (a configuration change or a manual wipe invalidated everything).
 */
internal object PrewarmedChapterIndex {

    /**
     * Warmed chapters for one configuration, grouped by manga.
     *
     * A manga missing from [byManga] is *unknown*, not empty: the index never claims a chapter is
     * cold, it only ever reports the chapters it has seen marked.
     */
    data class Snapshot(
        val configHash: String,
        val byManga: Map<Long, Set<Long>>,
    )

    /** Null until the first scan or marker; a cleared snapshot is null again. */
    private val state = MutableStateFlow<Snapshot?>(null)

    /** Exposed so a screen can follow the index instead of rescanning the cache directory. */
    val updates: StateFlow<Snapshot?> = state.asStateFlow()

    /**
     * Warmed chapters of [mangaId] for [configHash], or null when the answer is not known yet.
     *
     * A different [configHash] invalidates the whole snapshot: the markers it was built from carry
     * the hash in their name, so nothing in it can outlive an enhancement settings change.
     */
    fun get(mangaId: Long, configHash: String): Set<Long>? {
        val snapshot = state.value ?: return null
        if (snapshot.configHash != configHash) return null
        return snapshot.byManga[mangaId]
    }

    /** Records the full answer of a scan for [mangaId], replacing anything previously known. */
    fun put(mangaId: Long, configHash: String, ids: Set<Long>) {
        state.update { current ->
            val base = current.matching(configHash)
            base.copy(byManga = base.byManga + (mangaId to ids))
        }
    }

    /**
     * Adds a single chapter the moment its completion marker was written.
     *
     * Called from the cache writer thread, hence the atomic update. When nothing is known yet this
     * starts a snapshot holding just this chapter: reporting one warmed chapter is true, and the
     * scan that follows replaces it with the complete set.
     */
    fun markWarmed(mangaId: Long, chapterId: Long, configHash: String) {
        state.update { current ->
            val base = current.matching(configHash)
            base.copy(byManga = base.byManga + (mangaId to (base.byManga[mangaId].orEmpty() + chapterId)))
        }
    }

    /**
     * Forgets one chapter, e.g. because its cache directory (and with it the marker) was deleted.
     *
     * A manga the index knows nothing about is left as unknown rather than being turned into an
     * empty entry, so a later [get] still answers "ask the filesystem" instead of "nothing warmed".
     */
    fun removeChapter(mangaId: Long, chapterId: Long) {
        state.update { current ->
            val ids = current?.byManga?.get(mangaId) ?: return@update current
            if (chapterId !in ids) return@update current
            val remaining = ids - chapterId
            if (remaining.isEmpty()) {
                current.copy(byManga = current.byManga - mangaId)
            } else {
                current.copy(byManga = current.byManga + (mangaId to remaining))
            }
        }
    }

    /** Drops everything: the cache was wiped or the enhancement configuration changed. */
    fun clear() {
        state.value = null
    }

    /** [this] if it was built for [configHash], otherwise an empty snapshot for that hash. */
    private fun Snapshot?.matching(configHash: String): Snapshot {
        if (this != null && this.configHash == configHash) return this
        return Snapshot(configHash = configHash, byManga = emptyMap())
    }
}
