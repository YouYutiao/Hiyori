package eu.kanade.presentation.reader.settings

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * The ready-made quality levels above the expert settings.
 *
 * A preset is nothing but a set of preference values, never a hidden mode: [detect] only reports a
 * preset while every one of those values still matches, so moving a single slider flips the row to
 * [CUSTOM] instead of leaving a label that lies about the current setup. Nothing has to be undone
 * when the user edits an advanced value by hand.
 *
 * The values are valid for their model on every backend on purpose (Pro accepts denoise 0/3/4 and
 * 2x/3x only, SE accepts 2x/3x/4x, NPU allows both models), so the constraint fix-ups in the
 * settings page never rewrite a preset right after it was applied.
 *
 * The GPU performance mode is deliberately not part of a preset: it caps how hard the GPU is
 * pushed (and how hot the phone gets) rather than changing the image, so it stays a separate
 * choice. Balanced is exactly the stock configuration, which is why a fresh install reads as
 * Balanced instead of Custom.
 *
 * [CUSTOM] carries no values: it is the state where the advanced fields decide, and its chip is
 * what opens them.
 */
internal enum class QualityPreset(
    val titleRes: StringResource,
    val summaryRes: StringResource,
    val values: Values?,
) {
    FAST(
        titleRes = MR.strings.reader_quality_preset_fast,
        summaryRes = MR.strings.reader_quality_preset_fast_summary,
        values = Values(
            model = 0, // Real-CUGAN SE
            noise = 1, // 1x
            scale = 2,
            maxSize = 1280,
            tileSize = 96,
        ),
    ),
    BALANCED(
        titleRes = MR.strings.reader_quality_preset_balanced,
        summaryRes = MR.strings.reader_quality_preset_balanced_summary,
        values = Values(
            model = 0, // Real-CUGAN SE
            noise = 0, // none
            scale = 2,
            maxSize = 1600,
            tileSize = 128,
        ),
    ),
    HIGH(
        titleRes = MR.strings.reader_quality_preset_high,
        summaryRes = MR.strings.reader_quality_preset_high_summary,
        values = Values(
            model = 1, // Real-CUGAN Pro
            noise = 3, // 3x
            scale = 2,
            maxSize = 2048,
            tileSize = 192,
        ),
    ),
    CUSTOM(
        titleRes = MR.strings.reader_quality_preset_custom,
        summaryRes = MR.strings.reader_quality_preset_custom_summary,
        values = null,
    ),
    ;

    /**
     * The values a preset pins. [maxSize] is used for both dimensions, so a preset only matches
     * while the user has not set them apart.
     */
    data class Values(
        val model: Int,
        val noise: Int,
        val scale: Int,
        val maxSize: Int,
        val tileSize: Int,
    ) {
        init {
            // Guards the "a preset must survive the settings page's own fix-ups" promise: every
            // model on every backend accepts 2x, and Pro only accepts these denoise levels.
            require(scale == 2) { "Presets stay at 2x so no backend rewrites them" }
            require(model != 1 || noise in setOf(0, 3, 4)) {
                "Real-CUGAN Pro only accepts no denoise, 3x and conservative"
            }
        }
    }

    companion object {
        /** The preset whose values match exactly, or [CUSTOM] when any value was changed. */
        fun detect(values: Values): QualityPreset =
            entries.firstOrNull { it.values == values } ?: CUSTOM
    }
}
