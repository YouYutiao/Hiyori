package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.util.waifu2x.EnhancementStatusBus
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.PageEnhanceState
import eu.kanade.tachiyomi.util.waifu2x.PrewarmHitMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File

/**
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    internal val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
) : WebtoonBaseHolder(frame, viewer) {

    private data class DisplaySegment(
        val bytes: ByteArray,
        val isAnimated: Boolean,
        val enhancementVariant: String,
        /**
         * Enhanced cache entry for exactly these bytes, when one is already on disk. Purely a
         * hint for the first frame: a null entry means the segment is decoded from [bytes] now
         * and replaced by the enhanced image later, as before.
         */
        val cachedFile: File? = null,
    )

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    private val pageContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val segmentFrames = mutableListOf<ReaderPageImageView>()
    private val loadedSegments = linkedSetOf<Int>()
    private var pendingSelectionSequence: Int? = null
    private var lastSelectionSequence = -1

    /**
     * Latest state reported by each segment view. A page is several views, so the badge can only
     * be driven by their aggregate; publishing segment by segment would make it alternate between
     * "enhancing" and "enhanced" while the same page is still working.
     */
    private val segmentStates = mutableMapOf<Int, PageEnhanceState?>()

    /**
     * Bumped whenever the holder is bound or recycled. A segment callback captured before that
     * belongs to the previous page and must not publish for the current one.
     */
    private var bindGeneration = 0

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    /**
     * Job for watching enhancement cache saves for the bound page.
     */
    private var cacheEventJob: Job? = null

    init {
        frame.addView(pageContainer, 0, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refreshLayoutParams()
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        bindGeneration++
        segmentStates.clear()
        frame.pageIndex = page.index
        frame.mangaId = viewer.activity.viewModel.manga?.id ?: -1L
        frame.chapterId = page.chapter.chapter.id ?: -1L
        frame.readerPage = page
        frame.controlsCurrentPageSelection = false
        pendingSelectionSequence = null
        lastSelectionSequence = -1
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        observeCacheSaves()
        refreshLayoutParams()
    }

    /**
     * Swaps segments to their enhanced image the moment the cache entry lands.
     *
     * The window reaches one screen past the viewport on both sides: a segment whose enhancement
     * finishes just before it scrolls in is decoded then and there, so it arrives already sharp.
     * Waiting for selection instead (the old behaviour) meant the user saw the original for a
     * moment, then the swap, because the decode only started once the segment was on screen.
     */
    private fun observeCacheSaves() {
        cacheEventJob?.cancel()
        cacheEventJob = scope.launch {
            ImageEnhancementCache.events.collect { event ->
                val saved = event as? ImageEnhancementCache.CacheEvent.Saved ?: return@collect
                segmentFrames
                    .filter { it.matchesEvent(saved) && it.isWithinPredecodeWindow() }
                    .forEach { it.refreshFromCache() }
            }
        }
    }

    /**
     * Whether this segment is close enough to the viewport that decoding its enhanced strips now is
     * not wasted work. One screen above and one below: the strip one flick away is the one the
     * reader looks at next, and anything further out would only raise memory use.
     */
    private fun ReaderPageImageView.isWithinPredecodeWindow(): Boolean {
        if (isVisibleOnScreen()) return true
        if (!isShown) return false
        val location = IntArray(2)
        getLocationOnScreen(location)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val top = location[1]
        return top < screenHeight * 2 && top + height > -screenHeight
    }

    /**
     * Publishes the page's state as the best state of its segments.
     *
     * "Enhanced" only wins once nothing is still processing, so the badge cannot claim a page is
     * done while one of its strips is still being enhanced; a page where some strips were skipped
     * still reads as enhanced, because that is how it will look.
     */
    private fun onSegmentStateChanged(index: Int, state: PageEnhanceState?) {
        synchronized(segmentStates) { segmentStates[index] = state }
        publishAggregatedState()
    }

    private fun publishAggregatedState() {
        val page = page ?: return
        val chapterId = page.chapter.chapter.id ?: return
        val states = synchronized(segmentStates) { segmentStates.values.filterNotNull() }
        val aggregate = when {
            states.isEmpty() -> null
            states.any { it is PageEnhanceState.Processing } ->
                states
                    .filterIsInstance<PageEnhanceState.Processing>()
                    .maxByOrNull { it.progress ?: -1 }
            states.any { it == PageEnhanceState.Enhanced } -> PageEnhanceState.Enhanced
            else -> PageEnhanceState.Raw
        }
        EnhancementStatusBus.publish(chapterId, page.index, aggregate)
    }

    fun boundPage(): ReaderPage? = page

    fun notifyPageSelected(selectionSequence: Int) {
        if (lastSelectionSequence == selectionSequence) return
        pendingSelectionSequence = selectionSequence
        if (segmentFrames.isEmpty()) return

        lastSelectionSequence = selectionSequence
        segmentFrames.forEach { it.onPageSelected(true) }
        pendingSelectionSequence = null
        // Selecting the page must also refresh the badge: the aggregate may have changed while the
        // page was off screen and nothing was listening.
        publishAggregatedState()
    }

    fun preloadLeadingSegment() {
        segmentFrames.firstOrNull()?.promoteEnhancementRequest(highPriority = true)
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            marginEnd = viewer.recycler.sidePaddingPixels
            marginStart = viewer.recycler.sidePaddingPixels
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null
        cacheEventJob?.cancel()
        cacheEventJob = null
        bindGeneration++
        synchronized(segmentStates) { segmentStates.clear() }

        removeErrorLayout()
        loadedSegments.clear()
        pendingSelectionSequence = null
        lastSelectionSequence = -1
        segmentFrames.forEach { it.recycle() }
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator.setProgress(0)

        val page = page ?: return
        val streamFn = page.stream ?: return

        try {
            val segments = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                resolveCachedSegments(page, buildDisplaySegments(page, source))
            }
            withUIContext {
                bindDisplaySegments(page, segments)
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withContext(Dispatchers.Main) {
                setError(e)
            }
        }
    }

    private fun buildDisplaySegments(page: ReaderPage, source: BufferedSource): List<DisplaySegment> {
        val isAnimated = ImageUtil.isAnimatedAndSupported(source)
        if (isAnimated) {
            return listOf(
                DisplaySegment(
                    bytes = source.readByteArray(),
                    isAnimated = true,
                    enhancementVariant = page.enhancementKeySuffix,
                ),
            )
        }

        val splitSegments = ImageUtil.readerSegments(source.peek())
        if (splitSegments.isNullOrEmpty()) {
            return listOf(
                DisplaySegment(
                    bytes = source.readByteArray(),
                    isAnimated = false,
                    enhancementVariant = page.enhancementKeySuffix,
                ),
            )
        }

        // The loader cannot know that Webtoon will split this source, so it may already have
        // queued a full-page request. Segment variants are the only results displayed here;
        // cancel the redundant full-page work as soon as splitting is confirmed.
        val mangaId = page.chapter.chapter.mangaId
        val chapterId = page.chapter.chapter.id
        if (mangaId != null && chapterId != null) {
            ImageEnhancer.cancel(mangaId, chapterId, page.index, page.enhancementKeySuffix)
        }

        return splitSegments.mapIndexed { index, bytes ->
            DisplaySegment(
                bytes = bytes,
                isAnimated = false,
                // Shared with prewarm: a segment's cache key must be identical on both sides.
                enhancementVariant = ImageUtil.readerSegmentVariant(page.enhancementKeySuffix, index),
            )
        }
    }

    /**
     * Attaches an already enhanced cache entry to every display segment.
     *
     * A webtoon segment is enhanced from the exact bytes this holder decoded (see
     * [buildDisplaySegments]), so an entry written by prewarm is byte-for-byte the image the
     * reader would produce. When it exists the segment is bound straight from that file and
     * the page's first frame is the enhanced image. When it does not, nothing changes: the
     * original strips are decoded immediately and the enhanced image replaces them later, so
     * a miss never waits on inference. The whole probe is best effort - a cache that cannot be
     * read must never stop a page from opening.
     */
    private fun resolveCachedSegments(page: ReaderPage, segments: List<DisplaySegment>): List<DisplaySegment> {
        val preferences = viewer.readerPreferences
        if (!preferences.realCuganEnabled().get()) return segments
        val mangaId = page.chapter.chapter.mangaId ?: return segments
        val chapterId = page.chapter.chapter.id ?: return segments

        return try {
            ImageEnhancementCache.init(context)
            val configHash = ImageEnhancementCache.getConfigHash(preferences)
            PrewarmHitMetrics.recordCheck(mangaId, chapterId, page.index)
            var anyHit = false
            val resolved = segments.map { segment ->
                // An animated page keeps its animator: a cached still frame is not an upgrade for it.
                val file = if (segment.isAnimated) {
                    null
                } else {
                    ImageEnhancementCache.getCachedImage(
                        mangaId,
                        chapterId,
                        page.index,
                        configHash,
                        segment.enhancementVariant,
                    )
                }
                if (file == null) {
                    segment
                } else {
                    anyHit = true
                    segment.copy(cachedFile = file)
                }
            }
            if (anyHit) {
                PrewarmHitMetrics.recordHit(mangaId, chapterId, page.index)
            } else {
                PrewarmHitMetrics.recordMiss(mangaId, chapterId, page.index)
            }
            resolved
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Webtoon: enhancement cache probe failed for page ${page.index}" }
            segments
        }
    }

    private fun bindDisplaySegments(page: ReaderPage, segments: List<DisplaySegment>) {
        ensureSegmentFrames(segments.size)
        loadedSegments.clear()
        synchronized(segmentStates) { segmentStates.clear() }

        val displayConfig = ReaderPageImageView.Config(
            zoomDuration = viewer.config.doubleTapAnimDuration,
            minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
            cropBorders = viewer.config.imageCropBorders,
        )
        val generation = bindGeneration

        segments.forEachIndexed { index, segment ->
            val segmentFrame = segmentFrames[index]
            val streamFactory = { segment.bytes.inputStream() }

            segmentFrame.recycle()
            segmentFrame.pageIndex = page.index
            segmentFrame.mangaId = viewer.activity.viewModel.manga?.id ?: -1L
            segmentFrame.chapterId = page.chapter.chapter.id ?: -1L
            segmentFrame.readerPage = page
            // The first segment represents the logical page for queue prioritization and
            // pruning. Other segments share its page index but must not each claim to be the
            // focused target, otherwise a single tall image floods the high-priority queue.
            segmentFrame.controlsCurrentPageSelection = index == 0
            // The holder publishes one aggregate for the page instead; every segment reports to it,
            // so a strip that finishes while the user is looking further down still updates the
            // badge immediately.
            segmentFrame.enhancementStatusPublisher = false
            segmentFrame.onEnhanceStateChanged = { state ->
                if (generation == bindGeneration) onSegmentStateChanged(index, state)
            }
            segmentFrame.enhancementVariantOverride = segment.enhancementVariant
            segmentFrame.enhancementStreamOverride = streamFactory
            segmentFrame.suppressDefaultStatus = false
            segmentFrame.onImageLoaded = { onSegmentImageLoaded(index, segments.size) }
            segmentFrame.onImageLoadError = { error -> setError(error) }
            segmentFrame.onScaleChanged = { viewer.activity.hideMenu() }
            segmentFrame.onViewClicked = {}

            val cachedFile = segment.cachedFile
            if (cachedFile != null) {
                segmentFrame.setStripImage(cachedFile, displayConfig, streamFactory)
            } else {
                segmentFrame.setImage(
                    Buffer().write(segment.bytes),
                    segment.isAnimated,
                    displayConfig,
                    streamFactory,
                )
            }
        }

        pendingSelectionSequence?.let { notifyPageSelected(it) }
    }

    private fun ensureSegmentFrames(targetCount: Int) {
        while (segmentFrames.size < targetCount) {
            val segmentFrame = ReaderPageImageView(context, isWebtoon = true).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            segmentFrames += segmentFrame
            pageContainer.addView(segmentFrame)
        }

        while (segmentFrames.size > targetCount) {
            val removed = segmentFrames.removeLast()
            removed.recycle()
            pageContainer.removeView(removed)
        }
    }

    private fun onSegmentImageLoaded(index: Int, segmentCount: Int) {
        loadedSegments += index
        if (loadedSegments.size >= segmentCount) {
            onImageDecoded()
        }
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressContainer.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
