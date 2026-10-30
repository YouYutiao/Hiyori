package eu.kanade.tachiyomi.data.prewarm

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import logcat.LogPriority
import mihon.core.archive.archiveReader
import okio.Buffer
import okio.buffer
import okio.source
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.Closeable
import java.io.InputStream

/**
 * Lazy access to the reader segments of one page.
 *
 * [count] only reads the image header, so a chapter can be planned without splitting anything;
 * [bytes] cuts (and memoises) the page the first time a segment is actually dispatched, through
 * the same entry point the reader uses - see [ImageUtil.readerSegments].
 */
interface PrewarmPageSegments {
    /** Number of reader segments, or 0/1 when the page is displayed as a single image. */
    fun count(): Int

    /** One segment's JPEG bytes, as a lazy enhancement input. */
    fun bytes(segmentIndex: Int): Any?
}

/**
 * One prewarmable page of a chapter.
 *
 * @param index logical page index inside the chapter; the queue and the UI count these, so one
 *   element here is always one page of the chapter, never one of its segments.
 * @param dataProvider lazy opener for the whole page, following ImageEnhancer's dataProvider
 *   contract. Used when the page is not split into reader segments.
 * @param segments reader segments of this page when it is a tall page; null when this source
 *   cannot segment pages. The consumer expands these into per-segment work units.
 */
class PrewarmPage(
    val index: Int,
    val dataProvider: () -> Any?,
    val segments: PrewarmPageSegments? = null,
)

/**
 * Pages of one chapter plus a handle to release resources held while enhancing them
 * (e.g. the mmapped archive reader of a CBZ chapter).
 */
class PrewarmChapterPages(
    val pages: List<PrewarmPage>,
    private val closeAction: () -> Unit = {},
) : Closeable {
    override fun close() = closeAction()
}

interface PrewarmPageSource {
    /**
     * Lists the pages of a downloaded chapter; throws if the chapter is not usable on disk.
     *
     * One element per logical page, in reading order. Callers use the size of this list as the
     * chapter's page count, so page segmentation must not change it: split pages expose their
     * segments through [PrewarmPage.segments] instead.
     */
    suspend fun enumerate(manga: Manga, chapter: Chapter): PrewarmChapterPages
}

/**
 * Enumerates pages of downloaded chapters. Directory chapters mirror
 * DownloadManager.buildPageList; CBZ/zip chapters mirror ArchivePageLoader because
 * buildPageList cannot list inside an archive.
 *
 * Tall pages also expose the segments the webtoon reader displays, so prewarming a page writes
 * exactly the cache entries the reader looks up for it.
 */
class DownloadPrewarmPageSource(
    private val context: Context,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
) : PrewarmPageSource {

    override suspend fun enumerate(manga: Manga, chapter: Chapter): PrewarmChapterPages {
        val source = sourceManager.get(manga.source)
            ?: throw PrewarmException("Source ${manga.source} is not available")
        val chapterPath = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        ) ?: throw PrewarmException("Chapter not found on disk")

        return if (chapterPath.isFile) {
            enumerateArchive(chapterPath)
        } else {
            // The reader orders directory pages differently depending on the source: downloaded
            // chapters follow DownloadManager.buildPageList (plain lexicographic) while local
            // sources go through DirectoryPageLoader (case-insensitive natural order). Page index
            // is part of the cache key, so prewarming must mirror whichever loader will read it -
            // otherwise "2.jpg" can be served the enhancement cached for "10.jpg".
            enumerateDirectory(chapterPath, localSource = source.isLocalOrStub())
        }
    }

    private fun enumerateDirectory(chapterPath: UniFile, localSource: Boolean): PrewarmChapterPages {
        val files = chapterPath.listFiles().orEmpty()
            .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
            .let { list ->
                if (localSource) {
                    list.sortedWith { a, b ->
                        a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty())
                    }
                } else {
                    list.sortedBy { it.name }
                }
            }
        if (files.isEmpty()) throw PrewarmException("Chapter directory has no images")
        val store = ReaderSegmentStore()
        return PrewarmChapterPages(
            files.mapIndexed { index, file ->
                val openStream = { file.openInputStream() }
                PrewarmPage(
                    index = index,
                    dataProvider = { store.readWholePage(openStream) },
                    segments = store.segmentsOf(index, openStream),
                )
            },
            closeAction = { store.close() },
        )
    }

    private fun enumerateArchive(chapterPath: UniFile): PrewarmChapterPages {
        val reader = chapterPath.archiveReader(context)
        try {
            val names = reader.useEntries { entries ->
                entries
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .map { it.name }
                    .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                    .toList()
            }
            if (names.isEmpty()) throw PrewarmException("Archive has no images")
            val store = ReaderSegmentStore()
            return PrewarmChapterPages(
                names.mapIndexed { index, name ->
                    val openStream = { reader.getInputStream(name) }
                    PrewarmPage(
                        index = index,
                        dataProvider = { store.readWholePage(openStream) },
                        segments = store.segmentsOf(index, openStream),
                    )
                },
                closeAction = {
                    store.close()
                    reader.close()
                },
            )
        } catch (t: Throwable) {
            runCatching { reader.close() }
            throw t
        }
    }

    /**
     * Materialises reader segments, one page at a time.
     *
     * Counting a page only reads its header, so enumeration stays cheap; the split itself (region
     * decodes plus JPEG re-encodes) runs when the first segment of that page is dispatched. Cut
     * segments are then kept until the chapter run ends, because the queue drains depth by depth
     * and asks for the same page again on every pass.
     */
    private class ReaderSegmentStore {
        private val segmentCounts = HashMap<Int, Int>()
        private val segmentsByPage = HashMap<Int, List<ByteArray>>()
        private var retainedBytes = 0L

        fun segmentsOf(pageIndex: Int, openStream: () -> InputStream?): PrewarmPageSegments =
            object : PrewarmPageSegments {
                override fun count(): Int = this@ReaderSegmentStore.count(pageIndex, openStream)

                override fun bytes(segmentIndex: Int): Any? =
                    this@ReaderSegmentStore.bytes(pageIndex, segmentIndex, openStream)
            }

        fun readWholePage(openStream: () -> InputStream?): Any? {
            return try {
                openStream()?.use { Buffer().readFrom(it) }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Prewarm: Failed to read enhancement source" }
                null
            }
        }

        @Synchronized
        fun close() {
            if (segmentsByPage.isNotEmpty()) {
                logcat(LogPriority.DEBUG) { "Prewarm: released $retainedBytes bytes of reader segments" }
            }
            segmentCounts.clear()
            segmentsByPage.clear()
            retainedBytes = 0L
        }

        @Synchronized
        private fun count(pageIndex: Int, openStream: () -> InputStream?): Int {
            segmentCounts[pageIndex]?.let { return it }
            val count = try {
                // A streaming source, so only the image header is touched.
                openStream()?.use { stream -> ImageUtil.readerSegmentCount(stream.source().buffer()) } ?: 0
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Prewarm: Failed to plan reader segments" }
                0
            }
            segmentCounts[pageIndex] = count
            return count
        }

        @Synchronized
        private fun bytes(pageIndex: Int, segmentIndex: Int, openStream: () -> InputStream?): Any? {
            val segments = segmentsByPage[pageIndex] ?: splitPage(pageIndex, openStream) ?: return null
            val bytes = segments.getOrNull(segmentIndex) ?: return null
            return Buffer().write(bytes)
        }

        private fun splitPage(pageIndex: Int, openStream: () -> InputStream?): List<ByteArray>? {
            val source = readWholePage(openStream) as? Buffer ?: return null
            // Same entry point the reader uses, so the split is bit-for-bit the displayed one.
            val segments = ImageUtil.readerSegments(source) ?: return null
            segmentsByPage[pageIndex] = segments
            retainedBytes += segments.sumOf { it.size.toLong() }
            return segments
        }
    }

    class PrewarmException(message: String) : Exception(message)
}
