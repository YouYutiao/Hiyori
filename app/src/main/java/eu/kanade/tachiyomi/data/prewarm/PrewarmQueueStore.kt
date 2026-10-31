package eu.kanade.tachiyomi.data.prewarm

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.File

/**
 * One queued chapter as it is written to disk.
 *
 * Only identity and progress are stored; titles and the manga/chapter objects are read back from
 * the database on restore, so a renamed series cannot resurrect a stale row. Statuses are stored by
 * name - unlike ordinals, names survive someone reordering the enums later.
 */
@Serializable
data class PrewarmQueueEntry(
    val mangaId: Long,
    val chapterId: Long,
    val status: String = PrewarmStatus.QUEUED.name,
    val pauseReason: String = PrewarmPauseReason.NONE.name,
    val skipReason: String? = null,
    val queuedAt: Long = 0L,
    val totalPages: Int = 0,
    val pagesCompleted: Int = 0,
    val pagesCachedHits: Int = 0,
    val pagesFailed: Int = 0,
)

/**
 * The whole queue as it must come back after the process died.
 *
 * Finished chapters are not part of it: their pages already sit in the cache, and re-adding them
 * would make the next launch enumerate every one of them only to find them cached.
 */
@Serializable
data class PrewarmQueueSnapshot(
    val entries: List<PrewarmQueueEntry> = emptyList(),
    val sort: String = PrewarmSort.CUSTOM.name,
    /** A manual pause is user intent too - it must not turn into "resumed" after a restart. */
    val paused: Boolean = false,
)

/** Storage of [PrewarmQueueSnapshot]s; faked in unit tests. */
interface PrewarmQueueStore {
    /** The stored snapshot, or null when there is none (first run, unreadable file). */
    suspend fun restore(): PrewarmQueueSnapshot?

    suspend fun save(snapshot: PrewarmQueueSnapshot)
}

/**
 * Snapshot on disk, written the way [eu.kanade.tachiyomi.data.download.DownloadCache] writes its
 * index: through an [AtomicFile], so a kill mid-write can never leave a truncated file behind.
 *
 * It lives in `filesDir` rather than `cacheDir` on purpose. The download index is rebuildable by
 * scanning the filesystem; the prewarm queue is user intent and must not be evicted as cache.
 */
class FilePrewarmQueueStore(context: Context) : PrewarmQueueStore {

    private val file = AtomicFile(File(context.filesDir, QUEUE_FILE_NAME))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun restore(): PrewarmQueueSnapshot? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        try {
            val bytes = file.openRead().use { it.readBytes() }
            json.decodeFromString<PrewarmQueueSnapshot>(bytes.decodeToString())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Prewarm: unreadable queue snapshot, starting empty" }
            file.delete()
            null
        }
    }

    override suspend fun save(snapshot: PrewarmQueueSnapshot) = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        try {
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Prewarm: failed to persist the queue snapshot" }
        }
    }

    companion object {
        const val QUEUE_FILE_NAME = "prewarm_queue.json"
    }
}

/** What a stored entry resolves to: the queue row needs the real objects to run. */
data class PrewarmChapterRef(
    val manga: Manga,
    val chapter: Chapter,
    val sourceName: String,
)

/**
 * Looks up the manga/chapter behind a stored entry.
 *
 * A null result means the row is gone (series or chapter deleted while the app was closed) and the
 * entry must be dropped instead of being carried around forever.
 */
fun interface PrewarmQueueResolver {
    suspend fun resolve(mangaId: Long, chapterId: Long): PrewarmChapterRef?
}

/** Database-backed resolver; the source name is read through the stub fallback. */
class DatabasePrewarmQueueResolver(
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
) : PrewarmQueueResolver {

    override suspend fun resolve(mangaId: Long, chapterId: Long): PrewarmChapterRef? =
        withContext(Dispatchers.IO) {
            val chapter = getChapter.await(chapterId) ?: return@withContext null
            // A stored pair can go stale independently of the rows it points at.
            if (chapter.mangaId != mangaId) return@withContext null
            val manga = getManga.await(mangaId) ?: return@withContext null
            // getOrStub waits for extensions to load, so the worker's later lookup cannot race it.
            val sourceName = runCatching { sourceManager.getOrStub(manga.source).name }.getOrNull().orEmpty()
            PrewarmChapterRef(manga = manga, chapter = chapter, sourceName = sourceName)
        }
}

/** Flattens a queue row into the fields worth keeping across a restart. */
internal fun PrewarmItem.toQueueEntry(): PrewarmQueueEntry = PrewarmQueueEntry(
    mangaId = mangaId,
    chapterId = chapterId,
    status = status.name,
    pauseReason = pauseReason.name,
    skipReason = skipReason?.name,
    queuedAt = queuedAt,
    totalPages = totalPages,
    pagesCompleted = pagesCompleted,
    pagesCachedHits = pagesCachedHits,
    pagesFailed = pagesFailed,
)
