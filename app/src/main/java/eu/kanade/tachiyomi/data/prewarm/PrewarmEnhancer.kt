package eu.kanade.tachiyomi.data.prewarm

import android.content.Context
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer

/**
 * Seam over [ImageEnhancer] so the prewarm queue can be unit tested without the real
 * inference pipeline. Prewarm requests are always enqueued at preload priority so
 * reading keeps absolute priority over background enhancement.
 */
interface PrewarmEnhancer {
    fun enqueue(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        pageVariant: String,
        dataProvider: () -> Any?,
    )

    fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean

    fun isActivelyProcessing(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean

    fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = "")
}

class ImageEnhancerAdapter(private val context: Context) : PrewarmEnhancer {

    override fun enqueue(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        pageVariant: String,
        dataProvider: () -> Any?,
    ) {
        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            highPriority = false,
            pageVariant = pageVariant,
            dataProvider = dataProvider,
        )
    }

    override fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): Boolean =
        ImageEnhancer.hasRequest(mangaId, chapterId, pageIndex, pageVariant)

    override fun isActivelyProcessing(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): Boolean =
        ImageEnhancer.isActivelyProcessing(mangaId, chapterId, pageIndex, pageVariant)

    override fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String) {
        ImageEnhancer.cancel(mangaId, chapterId, pageIndex, pageVariant)
    }
}
