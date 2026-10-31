package eu.kanade.tachiyomi.data.prewarm

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * When the queue may run, as chosen by the user. Stored as an int so the two old
 * boolean preferences could be migrated without losing the user's choice.
 */
enum class PrewarmBatteryPolicy {
    /** Only while the device is plugged in. */
    CHARGE_ONLY,

    /** While charging, or on battery when it is sufficiently full. */
    SUFFICIENT_CHARGE,

    /** Regardless of the battery level, except for the hard low-battery floor. */
    ANY_TIME,
    ;

    companion object {
        fun fromStored(value: Int): PrewarmBatteryPolicy = entries.getOrElse(value) { CHARGE_ONLY }
    }
}

/** Reader-facing switches the prewarm queue must respect, faked in unit tests. */
interface PrewarmConfigSource {
    fun isPrewarmEnabled(): Boolean

    fun isEnhancementEnabled(): Boolean

    fun isAutoSplitEnabled(): Boolean

    fun batteryPolicy(): PrewarmBatteryPolicy

    fun currentConfigHash(): String

    /**
     * Emits whenever something that changes the enhanced output changes.
     *
     * Prewarming always processes with the reader's current settings - the hash is recomputed per
     * chapter - but an entry that already finished under the old settings would keep its "done"
     * state until the reader happens to clear the cache. Listening here re-queues it immediately.
     */
    fun configChanges(): Flow<Unit> = emptyFlow()
}

class ReaderPrewarmConfigSource(private val preferences: ReaderPreferences) : PrewarmConfigSource {

    override fun isPrewarmEnabled(): Boolean = preferences.prewarmEnabled().get()

    override fun isEnhancementEnabled(): Boolean = preferences.realCuganEnabled().get()

    override fun isAutoSplitEnabled(): Boolean = preferences.automaticSplitsPage().get()

    override fun batteryPolicy(): PrewarmBatteryPolicy =
        PrewarmBatteryPolicy.fromStored(preferences.prewarmBatteryPolicy().get())

    override fun currentConfigHash(): String = ImageEnhancementCache.getConfigHash(preferences)

    override fun configChanges(): Flow<Unit> = merge<Any>(
        preferences.realCuganModel().changes().drop(1),
        preferences.realEsrganStyle().changes().drop(1),
        preferences.realCuganNoiseLevel().changes().drop(1),
        preferences.realCuganScale().changes().drop(1),
        preferences.realCuganPrecision().changes().drop(1),
        preferences.realCuganProcessingBackend().changes().drop(1),
        preferences.realCuganFp16Arithmetic().changes().drop(1),
        preferences.realCuganMaxSizeWidth().changes().drop(1),
        preferences.realCuganMaxSizeHeight().changes().drop(1),
        preferences.realCuganSkipMaxSizeWidth().changes().drop(1),
        preferences.realCuganSkipMaxSizeHeight().changes().drop(1),
        preferences.realCuganTileSize().changes().drop(1),
        preferences.inkFilter().changes().drop(1),
        preferences.inkBleedingIntensity().changes().drop(1),
        preferences.inkBumpIntensity().changes().drop(1),
        preferences.inkOriginalIntensity().changes().drop(1),
    ).map { }
}
