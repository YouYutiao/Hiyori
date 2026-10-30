package eu.kanade.tachiyomi.data.prewarm

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Seam between the prewarm queue and the runtime environment (battery state, reader
 * activity). Injectable so the queue's gating logic can be unit tested without a device.
 */
interface PrewarmConstraints {
    fun isCharging(): Boolean

    /** Battery percentage in 0..100, or null when the platform cannot report it. */
    fun batteryLevel(): Int?

    fun isReaderActive(): Boolean

    /** Reported by ReaderActivity lifecycle callbacks. */
    fun setReaderActive(active: Boolean)
}

class AndroidPrewarmConstraints(private val context: Context) : PrewarmConstraints {

    @Volatile
    private var readerActive = false

    override fun isCharging(): Boolean {
        // Sticky system broadcast: no receiver registration or permission needed.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    override fun batteryLevel(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    override fun isReaderActive(): Boolean = readerActive

    override fun setReaderActive(active: Boolean) {
        readerActive = active
    }
}
