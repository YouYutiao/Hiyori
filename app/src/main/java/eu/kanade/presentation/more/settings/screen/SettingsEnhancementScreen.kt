package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.mediatek.MediaTekNpu
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Image enhancement settings outside the reader.
 *
 * The same controls the reader exposes, without the reader: when a page cannot be processed the
 * reader is the one place these switches cannot be reached from.
 */
object SettingsEnhancementScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_enhancement

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val preferences = remember { Injekt.get<ReaderPreferences>() }
        val scope = rememberCoroutineScope()

        val qualcommNpu = remember { Waifu2x.isQualcommNpuAvailable() }
        val mediaTekNpu = remember { MediaTekNpu.isDeviceSupported() }

        var cacheSize by remember { mutableStateOf<Long?>(null) }
        var confirmClear by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            cacheSize = withContext(Dispatchers.IO) {
                ImageEnhancementCache.totalSizeBytes(context)
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(MR.strings.pref_enhancement_cache)) },
                text = {
                    Text(
                        cacheSize?.takeIf { it > 0L }?.let(::formatBytes)
                            ?: stringResource(MR.strings.pref_enhancement_cache),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ImageEnhancementCache.clear(context)
                                }
                                cacheSize = withContext(Dispatchers.IO) {
                                    ImageEnhancementCache.totalSizeBytes(context)
                                }
                                context.toast(MR.strings.pref_enhancement_cache_cleared)
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // Route entries follow what the device can actually run, so this second entry point cannot
        // configure a route the reader would then crash on.
        val routeEntries = buildMap {
            put(Waifu2x.PROCESSING_BACKEND_VULKAN, stringResource(MR.strings.enhancement_route_vulkan))
            if (qualcommNpu) {
                put(
                    Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
                    stringResource(MR.strings.enhancement_route_npu),
                )
            }
            if (mediaTekNpu) {
                put(
                    Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU,
                    stringResource(MR.strings.enhancement_route_mtk),
                )
            }
        }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.realCuganEnabled(),
                title = stringResource(MR.strings.pref_enhancement_enabled),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.realCuganProcessingBackend(),
                entries = routeEntries,
                title = stringResource(MR.strings.reader_processing_backend),
                onValueChanged = {
                    // The reader serialises a backend change through restartNative; doing the same
                    // here keeps a queued decode from rebuilding the net behind the reset.
                    ImageEnhancer.restartNative("processing backend changed from settings")
                    true
                },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.realCuganModel(),
                entries = modelEntries(useNpuRoute = qualcommNpu || mediaTekNpu),
                title = stringResource(MR.strings.reader_model),
                onValueChanged = { model ->
                    // Real-CUGAN Pro only has denoise 0/3/4; carrying an impossible pair over
                    // would fail later while loading the model file.
                    val noise = preferences.realCuganNoiseLevel().get()
                    if (model == 1 && noise !in PRO_NOISE_LEVELS) {
                        preferences.realCuganNoiseLevel().set(PRO_NOISE_LEVELS.first())
                    }
                    ImageEnhancer.restartNative("model changed from settings")
                    true
                },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.realCuganNoiseLevel(),
                entries = noiseEntries(preferences.realCuganModel().get()),
                title = stringResource(MR.strings.reader_denoise_level),
                onValueChanged = {
                    ImageEnhancer.restartNative("denoise level changed from settings")
                    true
                },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.realCuganTileSize(),
                entries = mapOf(64 to "64", 96 to "96", 128 to "128", 192 to "192", 256 to "256"),
                title = stringResource(MR.strings.reader_tile_size),
                onValueChanged = {
                    ImageEnhancer.restartNative("tile size changed from settings")
                    true
                },
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.realCuganPreloadSize(),
                title = stringResource(MR.strings.reader_preload_pages),
                valueRange = 1..10,
                steps = 8,
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.realCuganMaxSizeWidth(),
                title = stringResource(MR.strings.reader_resolution_width),
                valueRange = 0..4000,
                steps = 19,
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.realCuganMaxSizeHeight(),
                title = stringResource(MR.strings.reader_resolution_height),
                valueRange = 0..6000,
                steps = 29,
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.pref_enhancement_cache),
            ) {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_enhancement_cache),
                    subtitle = cacheSize?.takeIf { it > 0L }?.let(::formatBytes),
                    onPreferenceClick = { confirmClear = true },
                )
            },
        )
    }
}

private val PRO_NOISE_LEVELS = listOf(0, 3, 4)

/** Same model set the reader offers for the current route. */
private fun modelEntries(useNpuRoute: Boolean): Map<Int, String> = buildMap {
    put(0, "Real-CUGAN SE")
    put(1, "Real-CUGAN Pro")
    put(Waifu2x.MODEL_REAL_ESRGAN_ANIME, "Real-ESRGAN")
    if (useNpuRoute) {
        put(Waifu2x.MODEL_W2XEX_PHOTO_SMALL, "W2xEX Photo Small")
        put(Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO, "SPAN NomosUni Photo")
    } else {
        put(3, "Real-CUGAN Nose")
        put(4, "Waifu2x")
        put(5, "Waifu2x (Fast)")
        put(6, "W2xEX Universal Fast")
        put(8, "W2xEX Omni Mini V2")
        put(Waifu2x.MODEL_W2XEX_PHOTO_SMALL, "W2xEX Photo Small")
        put(16, "AnimeJaNai v2 UltraCompact")
        put(18, "sudo UltraCompact")
        put(Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO, "SPAN NomosUni Photo")
    }
}

/** Real-CUGAN Pro only ships denoise 0/3/4; every other model offers the full range. */
private fun noiseEntries(model: Int): Map<Int, String> {
    val levels = if (model == 1) PRO_NOISE_LEVELS else listOf(0, 1, 2, 3, 4)
    return levels.associateWith { level ->
        when (level) {
            0 -> "Off"
            4 -> "Conservative"
            else -> "${level}x"
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val megabytes = bytes / 1024.0 / 1024.0
    return if (megabytes >= 1024) {
        String.format("%.1f GB", megabytes / 1024)
    } else {
        String.format("%.1f MB", megabytes)
    }
}
