package eu.kanade.tachiyomi.ui.prewarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.prewarm.PrewarmCompletionSummary
import eu.kanade.tachiyomi.data.prewarm.PrewarmItem
import eu.kanade.tachiyomi.data.prewarm.PrewarmManager
import eu.kanade.tachiyomi.data.prewarm.PrewarmSort
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PrewarmQueueViewModel(
    private val prewarmManager: PrewarmManager,
    private val preferences: ReaderPreferences,
) : ViewModel() {

    val queueState = prewarmManager.queueState
    val stats = prewarmManager.stats
    val isManualPaused = prewarmManager.manualPaused
    val sortMode: StateFlow<PrewarmSort> = prewarmManager.sortMode

    /** Result of the last run that reached an idle queue, shown after the queue empties. */
    val lastCompletion: StateFlow<PrewarmCompletionSummary?> = prewarmManager.lastCompletion

    /** Battery policy (see [ReaderPreferences.BATTERY_POLICY_CHARGE_ONLY]); edited in place. */
    val batteryPolicy: StateFlow<Int> = preferences.prewarmBatteryPolicy()
        .changes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = preferences.prewarmBatteryPolicy().get(),
        )

    /** The queue screen carries the charging rule inline so it can be relaxed in place. */
    fun setBatteryPolicy(policy: Int) {
        preferences.prewarmBatteryPolicy().set(policy)
    }

    fun pause() {
        prewarmManager.pause()
    }

    fun resume() {
        prewarmManager.resume()
    }

    /** Lets the current batch run on battery; the waiver ends with the batch. */
    fun prewarmNowOnce() {
        prewarmManager.prewarmNowOnce()
    }

    fun clearQueue() {
        prewarmManager.clearQueue()
    }

    fun cancel(item: PrewarmItem) {
        prewarmManager.cancel(item)
    }

    fun cancelSeries(mangaId: Long) {
        prewarmManager.cancelSeries(mangaId)
    }

    fun sortQueue(sort: PrewarmSort) {
        prewarmManager.sortQueue(sort)
    }

    fun moveChapter(chapterId: Long, toIndexInSeries: Int) {
        prewarmManager.moveChapter(chapterId, toIndexInSeries)
    }

    fun moveSeries(mangaId: Long, toIndex: Int) {
        prewarmManager.moveSeries(mangaId, toIndex)
    }

    fun moveSource(sourceId: Long, toIndex: Int) {
        prewarmManager.moveSource(sourceId, toIndex)
    }

    /** Cancels every chapter from one source, which is what the source header offers. */
    fun cancelSource(sourceId: Long) {
        queueState.value
            .filter { it.sourceId == sourceId }
            .map { it.mangaId }
            .distinct()
            .forEach(prewarmManager::cancelSeries)
    }
}
