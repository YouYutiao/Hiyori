package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.PrewarmAction
import eu.kanade.presentation.manga.PrewarmMenuCount
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.prewarm.PrewarmManager
import eu.kanade.tachiyomi.data.prewarm.PrewarmStatus
import eu.kanade.tachiyomi.data.prewarm.isActive
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.PrewarmedChapterIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class MangaViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val isFromSource: Boolean,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
    trackPreferences: TrackPreferences,
    private val readerPreferences: ReaderPreferences,
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val downloadManager: DownloadManager,
    private val prewarmManager: PrewarmManager,
    private val downloadPreferences: DownloadPreferences,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val addTracks: AddTracks,
    private val setMangaCategories: SetMangaCategories,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val sourceManager: SourceManager,
    private val refreshTracks: RefreshTracks,
    private val coverCache: CoverCache,
) : ViewModel() {

    val isCloudSyncAvailable: Boolean
        get() = source is HttpSource && downloadManager.isCloudSyncAvailable()

    fun runCloudSyncAction(chapters: List<Chapter>? = null) {
        val state = successState ?: return
        val chaptersToSync = chapters ?: state.chapters.map { it.chapter }
        viewModelScope.launchIO {
            val result = try {
                downloadManager.cloudSyncChapters(state.manga, chaptersToSync)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                snackbarHostState.showSnackbar(
                    context.stringResource(
                        MR.strings.cloud_sync_selected_manga_result,
                        0,
                        0,
                        chaptersToSync.size,
                    ),
                )
                return@launchIO
            }
            snackbarHostState.showSnackbar(
                context.stringResource(
                    MR.strings.cloud_sync_selected_manga_result,
                    result.queued,
                    result.skipped,
                    result.failed,
                ),
            )
        }
    }

    private val subscriberGate = SubscriberGate()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, isFromSource: Boolean): MangaViewModel
    }

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    /**
     * Emitted when the user asks to see the prewarm queue from the queueing snackbar.
     */
    private val openPrewarmQueueChannel = Channel<Unit>(Channel.BUFFERED)
    val openPrewarmQueue: Flow<Unit> = openPrewarmQueueChannel.receiveAsFlow()

    /** Chapters waiting for the "prewarm after download?" answer. */
    data class DownloadPrompt(val chapters: List<Chapter>, val startNow: Boolean)

    private val _downloadPrompt = MutableStateFlow<DownloadPrompt?>(null)
    val downloadPrompt: StateFlow<DownloadPrompt?> = _downloadPrompt.asStateFlow()

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isPrewarmAvailable: StateFlow<Boolean> = combine(
        readerPreferences.prewarmEnabled().changes(),
        readerPreferences.realCuganEnabled().changes(),
    ) { prewarmEnabled, enhancementEnabled -> prewarmEnabled && enhancementEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Enhancement master switch, surfaced so the manga screen can offer to turn it on. */
    val isEnhancementEnabled: StateFlow<Boolean> = readerPreferences.realCuganEnabled().changes()
        .map { it }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            readerPreferences.realCuganEnabled().get(),
        )

    fun setEnhancementEnabled(enabled: Boolean) {
        readerPreferences.realCuganEnabled().set(enabled)
    }

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds = MutableStateFlow(emptySet<Long>())

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val isRefreshingData = MutableStateFlow(false)

    private val downloadStates = MutableStateFlow(emptyMap<Long, Download.State>())

    // Progress ticks often, so only the download indicators that read it recompose instead of every tick rebuilding
    // the chapter list
    private val downloadProgress = mutableStateMapOf<Long, Int>()

    // A finished chapter leaves the queue, and its last status change can be lost with it, so its state is left to
    // the queried item once it's no longer queued
    private val queuedDownloadStates = combine(downloadStates, downloadManager.queueState) { states, queue ->
        val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
        states.filterKeys { it in queuedChapterIds }
    }

    private val hideMissingChapters = libraryPreferences.hideMissingChapters.get()

    private var hasPromptedToAddBefore = false

    /**
     * Chapters whose pages are all enhanced for the current configuration and that are not being
     * prewarmed right now, so the chapter row can carry a "warmed" badge.
     *
     * Seeded synchronously from [PrewarmedChapterIndex] so the badges are already right in the
     * first frame this screen composes, including the common case of a chapter that was warmed
     * moments earlier by the queue. The disk scan still owns the truth and overwrites this; the
     * index only remembers what the filesystem already told this process. Deriving it from
     * [PrewarmManager.prewarmedChapterIds] would be wrong for the same reason it always was: that
     * one lives in memory and would empty a whole prewarmed library on a process restart.
     */
    private val prewarmedChapterIds = MutableStateFlow<Set<Long>>(
        PrewarmedChapterIndex.get(mangaId, enhancementConfigHash()).orEmpty(),
    )

    private val defaultChapterFlagsJob = viewModelScope.launch {
        val manga = getMangaAndChapters.awaitManga(mangaId)
        if (!manga.favorite) {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    /**
     * Percent of a chapter that the prewarm queue has already enhanced, for chapters it is working
     * on right now. A chapter that is only queued has no percentage yet, and a finished one is
     * covered by [prewarmedChapterIds] instead.
     */
    private val prewarmProgressByChapter = prewarmManager.queueState
        .map { queue ->
            queue.mapNotNull { item ->
                val total = item.totalPages
                val running = item.status == PrewarmStatus.RUNNING || item.status == PrewarmStatus.PAUSED
                if (running && total > 0) item.chapterId to (item.pagesCompleted * 100 / total) else null
            }.toMap()
        }
        .distinctUntilChanged()

    private val mangaAndChapters = combine(
        flow {
            // So an entry outside the library isn't shown with its old chapter settings first
            defaultChapterFlagsJob.join()
            emitAll(getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true))
        }
            .distinctUntilChanged(),
        downloadCache.changes.pausedWhileUnsubscribed(),
        downloadManager.queueState.pausedWhileUnsubscribed(),
        prewarmedChapterIds,
        prewarmProgressByChapter,
    ) { (manga, chapters), _, _, prewarmedIds, prewarmProgress ->
        MangaAndChapters(
            manga = manga,
            source = sourceManager.getOrStub(manga.source),
            chapters = chapters.toChapterListItems(manga, prewarmedIds, prewarmProgress),
        )
    }

    private val scanlators = combine(
        getAvailableScanlators.subscribe(mangaId).distinctUntilChanged(),
        getExcludedScanlators.subscribe(mangaId).distinctUntilChanged(),
        ::Pair,
    )

    private val trackers = flow { emit(sourceManager.getOrStub(getMangaAndChapters.awaitManga(mangaId).source)) }
        .flatMapLatest { source ->
            combine(
                getTracks.subscribe(mangaId).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
        }
        .distinctUntilChanged()
        .onStart { emit(0 to false) }

    val state: StateFlow<State> = combine(
        mangaAndChapters,
        scanlators,
        trackers,
        combine(selectedChapterIds, queuedDownloadStates.pausedWhileUnsubscribed(), ::Pair),
        combine(dialog, isRefreshingData, ::Pair),
    ) {
            mangaAndChapters,
            (availableScanlators, excludedScanlators),
            (trackingCount, hasLoggedInTrackers),
            (selectedIds, downloads),
            (dialog, isRefreshingData),
        ->
        State.Success(
            manga = mangaAndChapters.manga,
            source = mangaAndChapters.source,
            isFromSource = isFromSource,
            chapters = mangaAndChapters.chapters.map { item ->
                val downloadState = downloads[item.id]
                val selected = item.id in selectedIds
                if (downloadState == null && !selected) return@map item
                item.copy(
                    downloadState = downloadState ?: item.downloadState,
                    selected = selected,
                )
            },
            availableScanlators = availableScanlators,
            excludedScanlators = excludedScanlators,
            trackingCount = trackingCount,
            hasLoggedInTrackers = hasLoggedInTrackers,
            isRefreshingData = isRefreshingData,
            dialog = dialog,
            hideMissingChapters = hideMissingChapters,
        )
    }
        // Composition would otherwise build these on first read, on the main thread, for every new state
        .onEach {
            it.processedChapters
            it.chapterListItems
            it.isAnySelected
            it.selectedChapters
            it.hasUnreadChapters
            it.hasReadChapters
            it.missingChapterCount
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope + subscriberGate, subscriberGate.whileSubscribed(5.seconds), State.Loading)

    /**
     * Everything that can change which chapters are warmed.
     *
     * Kept to flows that are multi-collector safe (state holders and replaying events): the cache
     * scan must never steal download cache events from [mangaAndChapters].
     */
    private val prewarmScanSignal: Flow<Unit> = merge(
        // The chapter list itself decides what can be warmed at all; the size is enough to notice
        // chapters being added, removed or refetched without rebuilding the list here.
        state.map { (it as? State.Success)?.chapters?.size }
            .distinctUntilChanged()
            .map { },
        // A queue entry leaving the active statuses is exactly the moment its chapter can become
        // fully warmed (a cancelled entry also changes nothing else we watch).
        prewarmManager.queueState
            .map { queue -> queue.map { item -> item.chapterId to item.status } }
            .distinctUntilChanged()
            .map { },
        // Cache cleanup: configuration change, manual wipe, discarded corrupt entries - all of
        // them make previously warmed chapters ordinary again.
        ImageEnhancementCache.events
            .filterIsInstance<ImageEnhancementCache.CacheEvent.Cleared>()
            .map { },
        // A finished download adds a chapter to the prewarmable set.
        downloadStates.map { },
    )

    init {
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.statusFlow()
                .filter { it.manga.id == mangaId }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(::updateDownloadState)
        }
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.progressFlow()
                .filter { it.manga.id == mangaId }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect { downloadProgress[it.chapter.id] = it.progress }
        }

        viewModelScope.launch {
            // The first answer must not wait: entering the screen is the one moment a missing
            // badge is noticeable. Later signals are bursty while a queue runs, so they keep the
            // debounce - a running prewarm completes a page every second or so, and rescanning for
            // each of them would only burn IO on a directory walk nobody is waiting for.
            merge(
                flowOf(Unit),
                prewarmScanSignal.debounce(PREWARM_SCAN_DEBOUNCE_MS),
            ).collectLatest { refreshPrewarmedChapterIds() }
        }

        viewModelScope.launch {
            // A chapter finished warming between two scans: follow the index instead of waiting to
            // be told to walk the directory again.
            PrewarmedChapterIndex.updates
                .mapNotNull { snapshot ->
                    when {
                        // Nothing known any more: the cache was wiped, so nothing is warmed.
                        snapshot == null -> emptySet()
                        snapshot.configHash != enhancementConfigHash() -> emptySet()
                        // This manga was never recorded: unknown, not cold - keep what we have.
                        else -> snapshot.byManga[mangaId]
                    }
                }
                .distinctUntilChanged()
                .collect { ids -> prewarmedChapterIds.value = ids - activePrewarmChapterIds() }
        }

        viewModelScope.launch {
            // The state loads the entry and its chapters anyway, so they aren't queried separately
            val loadedState = state.filterIsInstance<State.Success>().first()
            val manga = loadedState.manga
            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = loadedState.chapters.isEmpty()

            // Fetch info-chapters when needed
            if (needRefreshInfo || needRefreshChapter) {
                isRefreshingData.value = true
                fetchAllFromSource(
                    manga = manga,
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
                isRefreshingData.value = false
            }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            isRefreshingData.value = true
            fetchAllFromSource(
                manga = getMangaAndChapters.awaitManga(mangaId),
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            isRefreshingData.value = false
        }
    }

    private suspend fun fetchAllFromSource(
        manga: Manga,
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        try {
            val update = updateMangaFromRemote(
                source = sourceManager.getOrStub(manga.source),
                manga = manga,
                fetchDetails = fetchDetails,
                fetchChapters = fetchChapters,
                manualFetch = manualFetch,
            )
                .getOrThrow()

            if (manualFetch) {
                downloadNewChapters(update.newChapters)
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers(coverCache) != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    onRemoved()
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        dialog.value = Dialog.DuplicateManga(manga, duplicates)
                        return@launch
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                appScope.launch { addTracks.bindEnhancedTrackers(manga, state.source) }
            }
        }
    }

    fun showRemoveFromLibraryDialog() {
        if (successState?.manga?.favorite == true) dialog.value = Dialog.RemoveFromLibrary
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            dialog.value = Dialog.ChangeCategory(
                manga = manga,
                initialSelection = categories.mapAsCheckboxState { it.id in selection },
            )
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.SetFetchInterval(manga)
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launch {
            updateManga.awaitUpdateFetchInterval(
                // Custom intervals are negative
                manga.copy(fetchInterval = -interval),
            )
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        appScope.launch { downloadManager.deleteManga(state.manga, state.source) }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        viewModelScope.launch {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launch {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun updateDownloadState(download: Download) {
        val chapterId = download.chapter.id
        downloadStates.update {
            // Terminal states are derived by the queried item itself, so drop the override instead
            // of letting it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to download.status)
            }
        }
        if (download.status == Download.State.DOWNLOADING) {
            downloadProgress[chapterId] = download.progress
        } else {
            downloadProgress.remove(chapterId)
        }
    }

    fun getDownloadProgress(chapterId: Long): Int {
        return downloadProgress[chapterId] ?: 0
    }

    private fun List<Chapter>.toChapterListItems(
        manga: Manga,
        prewarmedChapterIds: Set<Long> = emptySet(),
        prewarmProgress: Map<Long, Int> = emptyMap(),
    ): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val queuedDownloads = if (isLocal) emptyMap() else downloadManager.getQueuedDownloadsByChapterId()
        val downloadedChapterIds = if (isLocal) emptySet() else downloadManager.getDownloadedChapterIds(this, manga)
        return map { chapter ->
            val activeDownload = queuedDownloads[chapter.id]
            val downloaded = isLocal || chapter.id in downloadedChapterIds
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                isUploadedToCloud = downloadPreferences.isChapterUploadedToCloud(chapter.id),
                // Enhanced pages only ever exist for chapters that are still on disk; a leftover
                // cache directory for a deleted download must not badge anything.
                isPrewarmed = downloaded && chapter.id in prewarmedChapterIds,
                prewarmProgress = prewarmProgress[chapter.id].takeIf { downloaded },
            )
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    Download.State.UPLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        if (successState == null) return
        if (startNow && chapters.singleOrNull()?.id == null) return

        // The download itself is unchanged; the only question is whether the finished chapters
        // should also be queued for prewarming, which is asked once and then remembered.
        when (readerPreferences.prewarmAfterDownload().get()) {
            ReaderPreferences.PREWARM_AFTER_DOWNLOAD_ALWAYS -> enqueueDownload(chapters, startNow, prewarm = true)
            ReaderPreferences.PREWARM_AFTER_DOWNLOAD_NEVER -> enqueueDownload(chapters, startNow, prewarm = false)
            else -> _downloadPrompt.value = DownloadPrompt(chapters, startNow)
        }
    }

    /** Answer to [downloadPrompt]; [dontAskAgain] stores the choice for future downloads. */
    fun resolveDownloadPrompt(prewarm: Boolean, dontAskAgain: Boolean) {
        val prompt = _downloadPrompt.value ?: return
        if (dontAskAgain) {
            readerPreferences.prewarmAfterDownload().set(
                if (prewarm) {
                    ReaderPreferences.PREWARM_AFTER_DOWNLOAD_ALWAYS
                } else {
                    ReaderPreferences.PREWARM_AFTER_DOWNLOAD_NEVER
                },
            )
        }
        _downloadPrompt.value = null
        enqueueDownload(prompt.chapters, prompt.startNow, prewarm)
    }

    /** Dismissing the prompt downloads without prewarming and keeps asking next time. */
    fun dismissDownloadPrompt() {
        resolveDownloadPrompt(prewarm = false, dontAskAgain = false)
    }

    /**
     * Queues the chapters for prewarming once their download finishes.
     *
     * Polling [DownloadManager.isChapterDownloaded] mirrors what the prewarm worker would see:
     * it reads the same directory cache, so a chapter is only handed over once it is really on
     * disk. The wait is bounded because a cancelled download must not keep a coroutine forever.
     */
    private fun queuePrewarmAfterDownload(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        appScope.launch {
            val pending = chapters.associateBy { it.id }.toMutableMap()
            val deadline = System.currentTimeMillis() + PREWARM_AFTER_DOWNLOAD_TIMEOUT_MS
            while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val downloaded = pending.values.filter { chapter ->
                    downloadManager.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        manga.source,
                    )
                }
                if (downloaded.isNotEmpty()) {
                    downloaded.forEach { pending.remove(it.id) }
                    prewarmManager.queueChapters(manga, downloaded)
                }
                if (pending.isEmpty()) break
                delay(2_000)
            }
            if (pending.isNotEmpty()) {
                logcat(LogPriority.INFO) {
                    "Prewarm: stopped waiting for ${pending.size} chapter(s) that never finished downloading"
                }
            }
        }
    }

    private fun enqueueDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
        prewarm: Boolean,
    ) {
        if (successState == null) return
        val manga = successState?.manga ?: return
        val startNowId = if (startNow) chapters.singleOrNull()?.id ?: return else null

        viewModelScope.launch {
            // The queueing outlives the screen, the add to library prompt doesn't
            appScope.launch {
                if (startNowId != null) {
                    downloadManager.startDownloadNow(startNowId)
                } else {
                    downloadChapters(chapters)
                }
                if (prewarm) queuePrewarmAfterDownload(manga, chapters)
            }.join()
            if (startNowId == null) toggleAllSelection(false)

            if (!isFavorited && !hasPromptedToAddBefore) {
                hasPromptedToAddBefore = true
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
            ChapterDownloadAction.PREWARM -> {
                val manga = successState?.manga ?: return
                val chapters = items.map { it.chapter }
                viewModelScope.launch {
                    prewarmManager.queueChapters(manga, chapters)
                }
            }
            ChapterDownloadAction.PREWARM_NOW -> {
                items.firstOrNull()?.let { prewarmManager.prewarmNow(it.id) }
            }
            ChapterDownloadAction.PREWARM_CANCEL -> {
                items.firstOrNull()?.let { prewarmManager.cancelChapter(it.id) }
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    /**
     * All chapters of the current screen state in reading order, regardless of download state.
     *
     * Prewarm has its own selectors: [getUnreadChapters], [getUnreadChaptersSorted] and
     * [getBookmarkedChapters] keep only *not yet downloaded* chapters for the download flow, which
     * would make every prewarm candidate set empty.
     */
    private fun orderedChaptersForPrewarm(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chapterItems = if (skipFiltered) filteredChapters else allChapters
        val sorted = chapterItems.orEmpty()
            .map { it.chapter }
            .sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) sorted.reversed() else sorted
    }

    /**
     * Ids of [chapters] whose pages are already on disk (L1 check, index only).
     *
     * A local source has no download bookkeeping, but every chapter is on disk already. For remote
     * sources this is a single batched cache lookup - never a per-chapter disk probe, because the
     * menu counts run for every action on every chapter list emission.
     */
    private fun prewarmAvailableIds(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source,
    ): Set<Long> = if (source.isLocalOrStub()) {
        chapters.mapTo(HashSet()) { it.id }
    } else {
        downloadManager.getDownloadedChapterIds(chapters, manga)
    }

    /** Configuration hash every warmed marker of this screen is expressed in. */
    private fun enhancementConfigHash(): String = ImageEnhancementCache.getConfigHash(readerPreferences)

    /**
     * Chapters with a live prewarm queue entry.
     *
     * Such a chapter has no completion marker yet and would make the badge flicker while its
     * remaining pages are written, so every path that produces the badge set subtracts them.
     */
    private fun activePrewarmChapterIds(): Set<Long> = prewarmManager.queueState.value
        .filter { it.status.isActive }
        .mapTo(HashSet()) { it.chapterId }

    /**
     * Recomputes [prewarmedChapterIds] for the configuration in use right now.
     *
     * Everything here touches the filesystem, so it runs on [Dispatchers.IO]: one listing of the
     * manga's cache directory, then one existence check per chapter for its completion marker.
     */
    private suspend fun refreshPrewarmedChapterIds() {
        val success = state.value as? State.Success ?: return
        val manga = success.manga
        val chapters = success.chapters.map { it.chapter }
        if (chapters.isEmpty()) {
            prewarmedChapterIds.value = emptySet()
            return
        }

        val configHash = enhancementConfigHash()
        // Already answered for this configuration in this process: re-walking the directory would
        // only repeat what the index holds, and the index is updated by every writer.
        val known = PrewarmedChapterIndex.get(manga.id, configHash)
        if (known != null) {
            prewarmedChapterIds.value = known - activePrewarmChapterIds()
            return
        }

        val startedAt = android.os.SystemClock.elapsedRealtime()
        val warmed = withContext(Dispatchers.IO) {
            // The cache root is only bound once something touched the cache in this process.
            ImageEnhancementCache.init(context)
            ImageEnhancementCache.prewarmedChapterIds(manga.id, configHash)
        }
        // The index stores the raw on-disk answer; the in-progress subtraction belongs to the
        // screen, so a chapter that finishes later is still found by the next lookup.
        PrewarmedChapterIndex.put(manga.id, configHash, warmed)
        prewarmedChapterIds.value = warmed - activePrewarmChapterIds()
        logcat(LogPriority.DEBUG) {
            "Prewarm badge scan: ${warmed.size}/${chapters.size} chapter(s) warmed in " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms"
        }
    }

    /**
     * Per-action "N chapters (M downloaded)" counts for the prewarm menu, so actions that
     * would queue nothing are visibly disabled instead of failing silently afterwards.
     */
    val prewarmMenuCounts: StateFlow<Map<PrewarmAction, PrewarmMenuCount>> = combine(
        state,
        downloadStates,
        // Same source runPrewarmAction reads, so the menu and the action never disagree about
        // readiness. It flips exactly when a snapshot becomes trusted (disk cache read or scan
        // completed), which is the only moment the counts need to be recomputed.
        downloadCache.hasIndexSnapshot,
    ) { screenState, _, hasIndexSnapshot ->
        val success = screenState as? MangaViewModel.State.Success ?: return@combine emptyMap()
        computePrewarmMenuCounts(success.manga, success.source, hasIndexSnapshot)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun computePrewarmMenuCounts(
        manga: Manga,
        source: Source,
        hasIndexSnapshot: Boolean,
    ): Map<PrewarmAction, PrewarmMenuCount> {
        // Until the index has a snapshot every lookup answers "nothing is downloaded"; show the
        // neutral "no counts yet" menu instead of a wall of disabled items. Local sources never
        // consult the index.
        if (!source.isLocalOrStub() && !hasIndexSnapshot) return emptyMap()
        // Ordering and availability are computed once; the per-action selection only slices them,
        // so the menu stays free of repeated cache queries.
        val ordered = orderedChaptersForPrewarm()
        val availableIds = prewarmAvailableIds(ordered, manga, source)
        return PrewarmAction.entries.associateWith { action ->
            val candidates = PrewarmSelection.select(action, ordered, availableIds)
            PrewarmMenuCount(total = candidates.total, downloaded = candidates.downloaded)
        }
    }

    fun runPrewarmAction(action: PrewarmAction) {
        val (manga, source) = successState?.let { it.manga to it.source } ?: return
        // Lookups answer "nothing is downloaded" until the download index has a snapshot. Saying so is
        // better than reporting every chapter as missing. Local sources never consult the index.
        if (!source.isLocalOrStub() && !downloadCache.hasIndexSnapshot.value) {
            viewModelScope.launch {
                snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.prewarm_index_scanning),
                    withDismissAction = true,
                )
            }
            return
        }
        val ordered = orderedChaptersForPrewarm()
        val availableIds = prewarmAvailableIds(ordered, manga, source)
        val candidates = PrewarmSelection.select(action, ordered, availableIds)
        // Prewarm only applies to chapters already on disk. A local source always has
        // everything available, so it can never reach this branch.
        if (candidates.available.isEmpty()) {
            // Distinguish "nothing to read here" from "the window is not downloaded yet":
            // both used to be reported as "no downloaded chapters", which hid the real cause.
            val message = if (candidates.isWindowEmpty) {
                context.stringResource(
                    if (action == PrewarmAction.BOOKMARKED_CHAPTERS) {
                        MR.strings.prewarm_no_bookmarked_chapters
                    } else {
                        MR.strings.prewarm_no_unread_chapters
                    },
                )
            } else {
                context.pluralStringResource(
                    MR.plurals.prewarm_candidates_not_downloaded,
                    candidates.total,
                    candidates.total,
                )
            }
            viewModelScope.launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                )
            }
            return
        }
        // Explain what prewarming does the first time something is actually queued.
        if (!readerPreferences.prewarmEducatedShown().get()) {
            pendingPrewarmEducation.value = PendingPrewarmEducation(
                manga = manga,
                chapters = candidates.available,
                skipped = candidates.skipped,
            )
            return
        }
        enqueuePrewarm(manga, candidates.available, candidates.skipped)
    }

    /** A first-run explanation waiting to be shown, with the batch it was requested for. */
    data class PendingPrewarmEducation(
        val manga: Manga,
        val chapters: List<Chapter>,
        val skipped: Int,
    )

    /** The first-run explanation is pending; null when nothing to explain. */
    val pendingPrewarmEducation: MutableStateFlow<PendingPrewarmEducation?> = MutableStateFlow(null)

    fun dismissPrewarmEducation(startPrewarm: Boolean) {
        val pending = pendingPrewarmEducation.value ?: return
        pendingPrewarmEducation.value = null
        readerPreferences.prewarmEducatedShown().set(true)
        if (startPrewarm) enqueuePrewarm(pending.manga, pending.chapters, pending.skipped)
    }

    private fun enqueuePrewarm(manga: Manga, availableChapters: List<Chapter>, skipped: Int) {
        // The queueing outlives the screen
        appScope.launch {
            val queuedCount = prewarmManager.queueChapters(manga, availableChapters, source?.name.orEmpty())
            viewModelScope.launch {
                // Nothing new was queued: say so instead of silently doing nothing, otherwise a
                // second tap on the same batch looks like the button is broken.
                if (queuedCount == 0) {
                    snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.prewarm_already_queued),
                        withDismissAction = true,
                    )
                    return@launch
                }
                // A waived charger requirement means this batch starts right away.
                val message = when {
                    skipped > 0 -> context.pluralStringResource(
                        MR.plurals.prewarm_queued_partial_snackbar,
                        queuedCount,
                        queuedCount,
                        skipped,
                    )
                    prewarmManager.isChargeWaivedOnce -> context.pluralStringResource(
                        MR.plurals.prewarm_queued_snackbar_now,
                        queuedCount,
                        queuedCount,
                    )
                    else -> context.pluralStringResource(
                        MR.plurals.prewarm_queued_snackbar,
                        queuedCount,
                        queuedCount,
                    )
                }
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = context.stringResource(MR.strings.action_view),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openPrewarmQueueChannel.send(Unit)
                }
            }
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        viewModelScope.launch {
            downloadManager.cancelQueuedDownloads(listOf(activeDownload))
            updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
        }
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launch {
            // The read state is written even if the screen closes, the tracking prompt isn't
            appScope.launch {
                setReadStatus.await(
                    read = read,
                    chapters = chapters.toTypedArray(),
                )
            }.join()

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launch
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launch
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                return@launch
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers() {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withContext(Dispatchers.Main) {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private suspend fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launch {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(it.id) { bookmark = bookmarked } }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        // Outlives the screen, which the delete dialog usually closes along with
        appScope.launch {
            try {
                downloadManager.deleteChapters(chapters, state.manga, state.source)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        appScope.launch {
            val manga = successState?.manga ?: return@launch
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
                // An automatic download cannot ask, so it follows whatever the user answered
                // the last time they were asked.
                if (readerPreferences.prewarmAfterDownload().get() == ReaderPreferences.PREWARM_AFTER_DOWNLOAD_ALWAYS) {
                    queuePrewarmAfterDownload(manga, chaptersToDownload)
                }
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        appScope.launch {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            appScope.launch {
                libraryPreferences.setChapterSettingsDefault(manga)
                if (applyToExisting) {
                    setMangaDefaultChapterFlags.awaitAll()
                }
            }.join()
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        val chapters = successState?.processedChapters ?: return
        val selectedIndex = chapters.indexOfFirst { it.id == item.chapter.id }
        if (selectedIndex < 0) return

        // Read from the ids, as the state catches up with a selection only after it's been made
        val selectedIds = HashSet(selectedChapterIds.value)
        if ((item.id in selectedIds) == selected) return

        val firstSelection = chapters.none { it.id in selectedIds }
        selectedIds.addOrRemove(item.id, selected)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = selectedIndex
                selectedPositions[1] = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < selectedPositions[0]) {
                    range = selectedIndex + 1..<selectedPositions[0]
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    range = (selectedPositions[1] + 1)..<selectedIndex
                    selectedPositions[1] = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach { selectedIds.add(chapters[it].id) }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (selectedIndex == selectedPositions[0]) {
                    selectedPositions[0] = chapters.indexOfFirst { it.id in selectedIds }
                } else if (selectedIndex == selectedPositions[1]) {
                    selectedPositions[1] = chapters.indexOfLast { it.id in selectedIds }
                }
            } else {
                if (selectedIndex < selectedPositions[0]) {
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    selectedPositions[1] = selectedIndex
                }
            }
        }
        selectedChapterIds.value = selectedIds
    }

    fun toggleAllSelection(selected: Boolean) {
        selectedChapterIds.value = if (selected) allChapters.orEmpty().mapTo(HashSet()) { it.id } else emptySet()
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        val selectedIds = selectedChapterIds.value
        selectedChapterIds.value = allChapters.orEmpty().filterNot { it.id in selectedIds }.mapTo(HashSet()) { it.id }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    // Chapters list - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object RemoveFromLibrary : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        dialog.value = Dialog.DeleteChapters(chapters)
    }

    fun showSettingsDialog() {
        dialog.value = Dialog.SettingsSheet
    }

    fun showTrackDialog() {
        dialog.value = Dialog.TrackSheet
    }

    fun showCoverDialog() {
        dialog.value = Dialog.FullCover
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.Migrate(target = manga, current = duplicate)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launch {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    private data class MangaAndChapters(val manga: Manga, val source: Source, val chapters: List<ChapterList.Item>)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hideMissingChapters: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val selectedChapters by lazy {
                processedChapters.fastFilter { it.selected }
            }

            val hasUnreadChapters by lazy {
                processedChapters.fastAny { !it.chapter.read }
            }

            val hasReadChapters by lazy {
                chapters.fastAny { it.chapter.read }
            }

            val missingChapterCount by lazy {
                if (hideMissingChapters) return@lazy 0
                processedChapters.fastMap { it.chapter.chapterNumber }.missingChaptersCount()
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                val chapterSort = getChapterSort(manga)
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> chapterSort(chapter1, chapter2) }
            }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val isUploadedToCloud: Boolean = false,
        val selected: Boolean = false,
        /** Every page of the chapter already has an enhanced cache entry for the active config. */
        val isPrewarmed: Boolean = false,
        /** Percent already enhanced by a running prewarm, or null when it is not being prewarmed. */
        val prewarmProgress: Int? = null,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

/** Quiet window for the prewarm cache scan, so a running queue doesn't trigger a walk per page. */
private const val PREWARM_SCAN_DEBOUNCE_MS = 300L

/** How long a queued download may stay unfinished before the after-download prewarm wait gives up. */
private const val PREWARM_AFTER_DOWNLOAD_TIMEOUT_MS = 3 * 60 * 60 * 1000L
