package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * Instantaneous power draw of the whole device, in watts, plus the battery level it draws from.
 *
 * Android exposes the battery current (`BATTERY_PROPERTY_CURRENT_NOW`, µA) and the pack voltage
 * (`ACTION_BATTERY_CHANGED`, mV). Their product is what the phone is pulling right now: the only
 * wattage an unprivileged app can read. It covers the whole device - screen, radios, SoC - not just
 * this app, so the reader labels it as the device's power draw.
 *
 * Devices differ: some report 0, `Int.MIN_VALUE` or a value in mA instead of µA, and a few keep a
 * positive current while charging. Every sample is sanity-checked, and an implausible one is
 * dropped so the overlay shows nothing rather than a fake number.
 */
class PowerUsageSampler(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager? = appContext.getSystemService(BatteryManager::class.java)

    /**
     * Battery level in percent, used as the bar of the power plate.
     *
     * A wattage on its own has no scale; "how much is left while drawing this" does. Read from
     * `BATTERY_PROPERTY_CAPACITY` and, on devices that do not answer that property, from the
     * sticky battery broadcast.
     */
    fun batteryPercent(): Int? {
        val capacity = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            null
        }
        if (capacity != null && capacity in 0..100) return capacity

        val intent = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    /** Watts drawn by the device, or null when the battery cannot report current/voltage. */
    fun sampleWatts(): Float? {
        val currentMicroAmps = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (_: Exception) {
            null
        } ?: return null
        if (currentMicroAmps == 0 || currentMicroAmps == Int.MIN_VALUE) return null

        val voltageMilliVolts = batteryVoltageMilliVolts() ?: return null
        if (voltageMilliVolts <= 0) return null

        val watts = abs(currentMicroAmps) / 1_000_000f * (voltageMilliVolts / 1_000f)
        // Outside this window the reading is a sensor artifact or reported in the wrong unit.
        return watts.takeIf { it in MIN_PLAUSIBLE_WATTS..MAX_PLAUSIBLE_WATTS }
    }

    private fun batteryVoltageMilliVolts(): Int? {
        return try {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MIN_PLAUSIBLE_WATTS = 0.05f
        const val MAX_PLAUSIBLE_WATTS = 25f
    }
}
