package eu.kanade.tachiyomi.data.prewarm

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache

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
}

class ReaderPrewarmConfigSource(private val preferences: ReaderPreferences) : PrewarmConfigSource {

    override fun isPrewarmEnabled(): Boolean = preferences.prewarmEnabled().get()

    override fun isEnhancementEnabled(): Boolean = preferences.realCuganEnabled().get()

    override fun isAutoSplitEnabled(): Boolean = preferences.automaticSplitsPage().get()

    override fun batteryPolicy(): PrewarmBatteryPolicy =
        PrewarmBatteryPolicy.fromStored(preferences.prewarmBatteryPolicy().get())

    override fun currentConfigHash(): String = ImageEnhancementCache.getConfigHash(preferences)
}
