package eu.kanade.tachiyomi.data.prewarm

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Read-only facade over [ImageEnhancementCache] for the prewarm queue. Keeps the
 * enhancement pipeline untouched while letting unit tests fake cache behavior.
 */
interface PrewarmCacheBridge {
    val events: Flow<ImageEnhancementCache.CacheEvent>

    fun isCached(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ): Boolean

    fun isSkipped(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
    ): Boolean

    fun currentConfigHash(): String

    fun trimCache()

    /** Total bytes currently occupied by the enhancement cache on disk. */
    fun cacheSizeBytes(): Long

    /**
     * Advertises the chapter as fully enhanced for [configHash] so the "already warmed" badge can
     * find it after a process death. Best effort: callers must not fail a run over it.
     */
    suspend fun markWarmed(mangaId: Long, chapterId: Long, configHash: String)
}

class ImageEnhancementCacheBridge(
    private val context: Context,
    private val preferences: ReaderPreferences,
) : PrewarmCacheBridge {

    override val events: Flow<ImageEnhancementCache.CacheEvent>
        get() = ImageEnhancementCache.events

    override fun isCached(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String,
    ): Boolean = ImageEnhancementCache.isCached(mangaId, chapterId, pageIndex, configHash, pageVariant)

    override fun isSkipped(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String,
    ): Boolean = ImageEnhancementCache.isSkipped(mangaId, chapterId, pageIndex, configHash, pageVariant)

    override fun currentConfigHash(): String = ImageEnhancementCache.getConfigHash(preferences)

    override fun trimCache() {
        ImageEnhancementCache.checkAndTrim(context)
    }

    override fun cacheSizeBytes(): Long {
        // Mirrors ImageEnhancementCache's root directory; the constant is private there.
        val root = File(context.cacheDir, "realcugan_cache")
        return runCatching {
            root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
    }

    override suspend fun markWarmed(mangaId: Long, chapterId: Long, configHash: String) {
        ImageEnhancementCache.markChapterWarmed(mangaId, chapterId, configHash)
    }
}
