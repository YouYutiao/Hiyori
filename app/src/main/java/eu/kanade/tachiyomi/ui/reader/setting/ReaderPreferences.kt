@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.i18n.MR

@Inject
@SingleIn(AppScope::class)
class ReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {

    init {
        val model = preferenceStore.getInt("pref_realcugan_model", 0)
        val selectedModel = model.get()
        if (selectedModel == 20) {
            // Retired HFA2Real selection: use Real-ESRGAN, supported by both backends.
            model.set(2)
            preferenceStore.getInt("pref_realcugan_scale", 2).set(2)
        } else if (selectedModel in REMOVED_ENHANCEMENT_MODELS || selectedModel >= FIRST_CUSTOM_MODEL_ID) {
            model.set(8)
        }
        migrateEnhancementStatusVisibility()
        migrateEnhancementIndicatorMode()
        migrateEnhancementIndicatorAxes()
        migratePerformanceUsageItems()
        migratePrewarmBatteryPolicy()
    }

    // region General

    val bookPageTransitions = preferenceStore.getBoolean("pref_book_page_transitions", true)

    val pageTransitions: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_key", true)

    fun setBookPageTransitions(enabled: Boolean) {
        bookPageTransitions.set(enabled)
        if (enabled) pageTransitions.set(true)
    }

    val loadAheadOnlyOverWifi: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_reader_load_ahead_only_over_wifi",
        true,
    )

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val verticalNavigator: Preference<Set<ReadingMode>> = preferenceStore.getEnumSet(
        "pref_vertical_navigator",
        emptySet(),
    )

    val verticalNavigatorOnLeft: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_vertical_navigator_on_left",
        false,
    )

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt(
        "pref_vertical_navigator_height",
        65,
    )

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    fun pagerVerticalPadding() = preferenceStore.getInt("pager_vertical_padding", 0)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    fun waifu2xEnabled() = preferenceStore.getBoolean("pref_waifu2x_enabled", false)

    fun waifu2xNoiseLevel() = preferenceStore.getInt("pref_waifu2x_noise_level", 2)

    fun anime4kEnabled() = preferenceStore.getBoolean("pref_anime4k_enabled", false)

    fun anime4kMode() = preferenceStore.getInt("pref_anime4k_mode", 0) // 0: Fast, 1: High, 2: Ultra

    fun realCuganEnabled() = preferenceStore.getBoolean("pref_realcugan_enabled", false)

    fun realCuganNoiseLevel() = preferenceStore.getInt("pref_realcugan_noise_level", 0) // 0: No Denoise, 1: Denoise 1x, 2: Denoise 2x, 3: Denoise 3x, 4: Conservative

    fun realCuganScale() = preferenceStore.getInt("pref_realcugan_scale", 2) // 2x, 3x, 4x

    fun realCuganModel() = preferenceStore.getInt("pref_realcugan_model", 0)

    fun realEsrganStyle() = preferenceStore.getInt("pref_realesrgan_style", 0) // 0: Anime, 1: Photo

    fun realCuganPreloadSize() = preferenceStore.getInt("pref_realcugan_preload_size", 3)

    fun realCuganProEnabled() = preferenceStore.getBoolean("pref_realcugan_pro_enabled", false)

    fun realCuganPerformanceMode() = preferenceStore.getInt("pref_realcugan_performance_mode", 0) // 0: 90%, 1: 50%, 2: 30%

    fun realCuganTileSize() = preferenceStore.getInt("pref_realcugan_tile_size", 128)

    fun realCuganPrecision() = preferenceStore.getInt("pref_realcugan_precision", 0) // 0: FP16, 1: FP32, 3: BF16 (legacy 2 resolves to FP16)

    fun realCuganProcessingBackend() = preferenceStore.getInt(
        "pref_realcugan_processing_backend",
        Waifu2x.defaultProcessingBackend(),
    ) // 0: Vulkan, 1: Qualcomm, 2: MediaTek

    fun realCuganFp16Arithmetic() = preferenceStore.getBoolean("pref_realcugan_fp16_arithmetic", false)

    fun realCuganMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_max_size_width", 1600)

    fun realCuganMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_max_size_height", 1600)

    fun realCuganSkipMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_skip_max_size_width", 0)

    fun realCuganSkipMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_skip_max_size_height", 0)

    /** Legacy key, only read by [migrateEnhancementStatusVisibility]. */
    fun realCuganShowStatus() = preferenceStore.getBoolean("pref_realcugan_show_status", false)

    /**
     * Legacy key, only read by [migrateEnhancementIndicatorMode]. Retired in favour of
     * [enhancementIndicatorMode]; kept readable so upgrades keep their existing indicator.
     */
    fun enhancementStatusVisibility(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_status_visibility",
        STATUS_VISIBILITY_HIDDEN,
    )

    /**
     * Legacy single-choice mode, only read by [migrateEnhancementIndicatorAxes]. Retired in favour
     * of the three independent choices below: [enhancementIndicatorVisible],
     * [enhancementIndicatorPlacement] and [enhancementIndicatorStyle].
     */
    fun enhancementIndicatorMode(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_indicator_mode",
        INDICATOR_MODE_PAGE_NUMBER,
    )

    /** Whether the enhancement status is drawn at all; the same switch as "不显示 / 显示". */
    fun enhancementIndicatorVisible(): Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enhancement_indicator_visible",
        true,
    )

    /**
     * Where the status is drawn: any combination of [INDICATOR_PLACEMENT_LEFT],
     * [INDICATOR_PLACEMENT_RIGHT] and [INDICATOR_PLACEMENT_PAGE_NUMBER].
     *
     * The two corners are mutually exclusive because the performance overlay always needs the other
     * one; the page number marker can be added to either corner. See [togglePlacement].
     */
    fun enhancementIndicatorPlacement(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_indicator_placement_flags",
        INDICATOR_PLACEMENT_PAGE_NUMBER,
    )

    /** Retired single-value placement, only read by [migrateEnhancementIndicatorAxes]. */
    private fun legacyEnhancementIndicatorPlacement(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_indicator_placement",
        LEGACY_PLACEMENT_LEFT,
    )

    /** How the corner badges read, see [INDICATOR_STYLE_TEXT]: words or percentages. */
    fun enhancementIndicatorStyle(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_indicator_style",
        INDICATOR_STYLE_TEXT,
    )

    /**
     * Whether the performance readings are drawn: [PERFORMANCE_USAGE_HIDDEN] (0) or
     * [PERFORMANCE_USAGE_ALWAYS] (2).
     *
     * The retired "only while enhancing" value (1) is still stored on devices that picked it; it is
     * read as "shown" so nobody's overlay silently disappears after the option was removed.
     */
    fun performanceUsageMode(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_mode",
        PERFORMANCE_USAGE_HIDDEN,
    )

    /**
     * Which corner the readings use, [PERFORMANCE_CORNER_LEFT] or [PERFORMANCE_CORNER_RIGHT].
     *
     * While the enhancement status occupies a corner this value is ignored and the readings take
     * the other one, see [performanceCorner]; the stored choice comes back once that corner is free.
     */
    fun performanceUsagePlacement(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_placement",
        PERFORMANCE_CORNER_RIGHT,
    )

    /**
     * Which readings are drawn, as flags of [PERFORMANCE_ITEM_CPU], [PERFORMANCE_ITEM_POWER] and
     * [PERFORMANCE_ITEM_BATTERY]. At most [PERFORMANCE_ITEM_LIMIT] at a time, see
     * [togglePerformanceItem].
     */
    fun performanceUsageItems(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_items",
        PERFORMANCE_ITEM_CPU or PERFORMANCE_ITEM_POWER,
    )

    /** Text size of the readings overlay, same scale as [enhancementBadgeSize]. */
    fun performanceUsageSize(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_size",
        BADGE_SIZE_MEDIUM,
    )

    /** Font weight of the readings overlay, same scale as [enhancementBadgeWeight]. */
    fun performanceUsageWeight(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_weight",
        BADGE_WEIGHT_MEDIUM,
    )

    /**
     * Extra spacing of the readings from their own screen edge, in dp.
     *
     * Independent from [enhancementBadgeOffsetX] so the two overlays can be aligned separately;
     * measured from the side the readings sit on, exactly like the badge.
     */
    fun performanceUsageOffsetX(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_offset_x",
        BADGE_OFFSET_X_DEFAULT,
    )

    /** Extra bottom padding of the readings in dp, see [performanceUsageOffsetX]. */
    fun performanceUsageOffsetY(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_offset_y",
        BADGE_OFFSET_Y_DEFAULT,
    )

    /** Text size of the enhancement badge, see [BADGE_SIZE_SMALL]. */
    fun enhancementBadgeSize(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_badge_size",
        BADGE_SIZE_MEDIUM,
    )

    /** Font weight of the enhancement badge, see [BADGE_WEIGHT_NORMAL]. */
    fun enhancementBadgeWeight(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_badge_weight",
        BADGE_WEIGHT_MEDIUM,
    )

    /**
     * Extra spacing of the corner badge from its own screen edge, in dp.
     *
     * The badge sits in the corner chosen by [enhancementIndicatorPlacement] and never leaves it:
     * for the left corner this is the start padding, for the right corner the end padding, so
     * increasing it always moves the badge towards the page number.
     */
    fun enhancementBadgeOffsetX(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_badge_offset_x",
        BADGE_OFFSET_X_DEFAULT,
    )

    /** Extra bottom padding of the corner badge in dp, see [enhancementBadgeOffsetX]. */
    fun enhancementBadgeOffsetY(): Preference<Int> = preferenceStore.getInt(
        "pref_enhancement_badge_offset_y",
        BADGE_OFFSET_Y_DEFAULT,
    )

    /** Retired "both / cpu / power" choice, only read by [migratePerformanceUsageItems]. */
    private fun performanceUsageContent(): Preference<Int> = preferenceStore.getInt(
        "pref_performance_usage_content",
        PERFORMANCE_USAGE_CONTENT_BOTH,
    )

    /** One-shot widening of the retired three-way choice into the item flags. */
    private fun migratePerformanceUsageItems() {
        val items = performanceUsageItems()
        if (items.isSet()) return
        items.set(performanceItemsForLegacyContent(performanceUsageContent().get()))
    }

    /** Whether the one-time "what prewarm does" explanation has been shown. */
    fun prewarmEducatedShown() = preferenceStore.getBoolean("pref_prewarm_educated_shown", false)

    /**
     * When prewarming may run: [BATTERY_POLICY_CHARGE_ONLY] (0),
     * [BATTERY_POLICY_SUFFICIENT] (1) or [BATTERY_POLICY_ANY_TIME] (2).
     */
    fun prewarmBatteryPolicy(): Preference<Int> = preferenceStore.getInt(
        "pref_prewarm_battery_policy",
        BATTERY_POLICY_CHARGE_ONLY,
    )

    /** Set once the charging-only default has been lifted on an existing install. */
    fun prewarmAnyTimeMigrated(): Preference<Boolean> =
        preferenceStore.getBoolean("pref_prewarm_any_time_migrated", false)

    /**
     * One-shot mapping of the retired "only while charging" switch: on keeps the charger
     * requirement, off means the user was already fine with prewarming on battery.
     */
    private fun migratePrewarmBatteryPolicy() {
        val policy = prewarmBatteryPolicy()
        if (policy.isSet()) return
        policy.set(batteryPolicyForLegacyChargingRequirement(prewarmRequireCharging().get()))
    }

    /** Legacy key, only read by [migrateEnhancementIndicatorMode]. */
    fun pageNumberEnhancementMarker() = preferenceStore.getBoolean("pref_page_number_enhancement_marker", true)

    /**
     * One-shot read-old/write-new mapping from [realCuganShowStatus] so the upgraded install
     * keeps exactly the indicator it had before. The legacy key stays readable but unused.
     */
    private fun migrateEnhancementStatusVisibility() {
        val visibility = enhancementStatusVisibility()
        if (visibility.isSet()) return
        visibility.set(
            if (realCuganShowStatus().get()) {
                STATUS_VISIBILITY_ALWAYS
            } else {
                STATUS_VISIBILITY_HIDDEN
            },
        )
    }

    /**
     * One-shot read-old/write-new mapping from the retired "badge visibility + page number marker"
     * pair. "Only while enhancing" is folded into "badge", which is the closest of the four modes.
     */
    private fun migrateEnhancementIndicatorMode() {
        val mode = enhancementIndicatorMode()
        if (mode.isSet()) return
        val badge = enhancementStatusVisibility().get() != STATUS_VISIBILITY_HIDDEN
        val marker = pageNumberEnhancementMarker().get()
        mode.set(indicatorModeForLegacySettings(badgeVisible = badge, marker = marker))
    }

    /**
     * One-shot split of the retired single-choice mode into the three independent choices the
     * settings screen now exposes. Keyed on the placement, which only this migration writes.
     *
     * A build that already stored the single-value placement is translated directly; the visibility
     * and style it wrote stay as they are. Older installs are derived from the six-mode value.
     */
    private fun migrateEnhancementIndicatorAxes() {
        val placement = enhancementIndicatorPlacement()
        if (placement.isSet()) return
        val legacyPlacement = legacyEnhancementIndicatorPlacement()
        if (legacyPlacement.isSet()) {
            placement.set(placementFlagsForLegacyPlacement(legacyPlacement.get()))
            return
        }
        val mode = enhancementIndicatorMode().get()
        enhancementIndicatorVisible().set(indicatorVisibleForLegacyMode(mode))
        enhancementIndicatorStyle().set(indicatorStyleForLegacyMode(mode))
        placement.set(placementFlagsForLegacyMode(mode))
    }

    fun prewarmEnabled() = preferenceStore.getBoolean("pref_prewarm_enabled", true)

    /**
     * What to do when chapters are added to the download queue:
     * [PREWARM_AFTER_DOWNLOAD_ASK] (0), [PREWARM_AFTER_DOWNLOAD_ALWAYS] (1) or
     * [PREWARM_AFTER_DOWNLOAD_NEVER] (2).
     */
    fun prewarmAfterDownload(): Preference<Int> = preferenceStore.getInt(
        "pref_prewarm_after_download",
        PREWARM_AFTER_DOWNLOAD_ASK,
    )

    fun prewarmRequireCharging() = preferenceStore.getBoolean("pref_prewarm_require_charging", true)

    fun enhancementOnboardingShown() = preferenceStore.getBoolean("pref_enhancement_onboarding_shown", false)

    fun inkFilter() = preferenceStore.getBoolean("ink_filter", false)

    fun inkBleedingIntensity() = preferenceStore.getInt("ink_bleeding_intensity", 0)

    fun inkBumpIntensity() = preferenceStore.getInt("ink_bump_intensity", 0)

    fun inkOriginalIntensity() = preferenceStore.getInt("ink_original_intensity", 0)

    // endregion

    // region Split two-page spread

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    val dualPageView: Preference<DualPageView> = preferenceStore.getEnum(
        "pref_dual_page_view",
        DualPageView.NEVER,
    )

    fun pageLayout() = preferenceStore.getInt("page_layout", PageLayout.SINGLE_PAGE.value)

    fun automaticSplitsPage() = preferenceStore.getBoolean("automatic_splits_page", false)

    fun autoDetectPageSpreads() = preferenceStore.getBoolean("auto_detect_page_spreads", false)

    fun invertDoublePages() = preferenceStore.getBoolean("invert_double_pages", false)

    fun hingeGapSize() = preferenceStore.getInt("hinge_gap_size", 0)

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    // region WebGpu

    val transitionAnimation: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_transition_animation", TransitionAnimation.BASIC)

    val transitionAnimationDual: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_dual_transition_animation", TransitionAnimation.BASIC)

    val cutoutMode: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_cutout_mode", CutoutMode.AVOID)

    val cutoutModeDual: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_dual_cutout_mode", CutoutMode.IGNORE)

    val continuousMinWidth: Preference<Int> = preferenceStore.getInt("webgpu_continuous_minwidth", 100)

    val continuousGap: Preference<Int> = preferenceStore.getInt("webgpu_continuous_gap", 10)

    // endregion

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(
            MR.strings.tapping_inverted_horizontal,
            shouldInvertHorizontal = true,
        ),
        VERTICAL(
            MR.strings.tapping_inverted_vertical,
            shouldInvertVertical = true,
        ),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    enum class TransitionAnimation(val titleRes: StringResource) {
        BASIC(MR.strings.transition_animation_basic),
        FLIP(MR.strings.transition_animation_flip),
        FLIP_LEFT(MR.strings.transition_animation_flip_left),
        FLIP_RIGHT(
            MR.strings.transition_animation_flip_right,
        ),
        STACK_LEFT(MR.strings.transition_animation_stack_left),
        STACK_RIGHT(MR.strings.transition_animation_stack_right),
        STACK_UP(
            MR.strings.transition_animation_stack_up,
        ),
        STACK_DOWN(MR.strings.transition_animation_stack_down),
        SPHERE(MR.strings.transition_animation_sphere),
        CUBE_INSIDE(
            MR.strings.transition_animation_cube_inside,
        ),
        CUBE_OUTSIDE(MR.strings.transition_animation_cube_outside),
        FADE(MR.strings.transition_animation_fade),
        FADE_WHITE(
            MR.strings.transition_animation_fade_white,
        ),
        NONE(MR.strings.transition_animation_none),
    }

    enum class CutoutMode(val titleRes: StringResource) {
        IGNORE(MR.strings.cutout_mode_ignore),
        AVOID(MR.strings.cutout_mode_avoid),
        SHIFT(MR.strings.cutout_mode_shift),
    }

    enum class DualPageView(val titleRes: StringResource) {
        NEVER(MR.strings.dual_page_view_never),
        ALWAYS(MR.strings.dual_page_view_always),
        WIDE(MR.strings.dual_page_view_wide),
    }

    companion object {
        private const val FIRST_CUSTOM_MODEL_ID = 1000
        private val REMOVED_ENHANCEMENT_MODELS = setOf(7, 10, 11, 12, 13, 14, 15, 17)

        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25
        const val PAGER_VERTICAL_PADDING_MIN = 0
        const val PAGER_VERTICAL_PADDING_MAX = 64

        const val MILLI_CONVERSION = 100

        /**
         * Legacy status indicator visibility values, only read by [migrateEnhancementIndicatorMode].
         * See [enhancementStatusVisibility].
         */
        const val STATUS_VISIBILITY_HIDDEN = 0
        const val STATUS_VISIBILITY_PROCESSING_ONLY = 1
        const val STATUS_VISIBILITY_ALWAYS = 2

        /** Legacy enhancement indicator modes, only read by the migration to the three axes. */
        const val INDICATOR_MODE_HIDDEN = 0
        const val INDICATOR_MODE_BADGE = 1
        const val INDICATOR_MODE_PAGE_NUMBER = 2
        const val INDICATOR_MODE_BOTH = 3

        /** The same corner badge, drawn as a percentage ("20%") instead of the state word. */
        const val INDICATOR_MODE_BADGE_NUMERIC = 4
        const val INDICATOR_MODE_BOTH_NUMERIC = 5

        /** Placement flags, see [enhancementIndicatorPlacement]. */
        const val INDICATOR_PLACEMENT_LEFT = 1 shl 0
        const val INDICATOR_PLACEMENT_RIGHT = 1 shl 1
        const val INDICATOR_PLACEMENT_PAGE_NUMBER = 1 shl 2

        /** Retired single-value placements, only read by [migrateEnhancementIndicatorAxes]. */
        const val LEGACY_PLACEMENT_LEFT = 0
        const val LEGACY_PLACEMENT_RIGHT = 1
        const val LEGACY_PLACEMENT_PAGE_NUMBER = 2
        const val LEGACY_PLACEMENT_ALL = 3

        /** How the corner badges read, see [enhancementIndicatorStyle]. */
        const val INDICATOR_STYLE_TEXT = 0
        const val INDICATOR_STYLE_NUMERIC = 1

        /** Whether the placement draws the badge in the bottom-left corner. */
        fun drawsLeftBadge(placement: Int): Boolean = placement and INDICATOR_PLACEMENT_LEFT != 0

        /** Whether the placement draws the badge in the bottom-right corner. */
        fun drawsRightBadge(placement: Int): Boolean = placement and INDICATOR_PLACEMENT_RIGHT != 0

        /** Whether the placement draws a corner badge at all; the text/numeric choice applies to it. */
        fun drawsBadge(placement: Int): Boolean =
            placement and (INDICATOR_PLACEMENT_LEFT or INDICATOR_PLACEMENT_RIGHT) != 0

        /** Whether the placement marks enhanced pages on the page number indicator. */
        fun marksPageNumber(placement: Int): Boolean =
            placement and INDICATOR_PLACEMENT_PAGE_NUMBER != 0

        /**
         * Applies one tap on a placement chip.
         *
         * The two corners stay mutually exclusive and at least one placement has to remain, so a tap
         * that would clear the last one is ignored and returns the placement unchanged.
         */
        fun togglePlacement(placement: Int, chip: Int): Int {
            val next = when (chip) {
                INDICATOR_PLACEMENT_LEFT, INDICATOR_PLACEMENT_RIGHT -> {
                    if (placement and chip != 0) {
                        placement and chip.inv()
                    } else {
                        (placement and INDICATOR_PLACEMENT_LEFT.inv() and INDICATOR_PLACEMENT_RIGHT.inv()) or chip
                    }
                }
                else -> placement xor chip
            }
            return if (next == 0) placement else next
        }

        /** Legacy six-mode value → placement flags, used once by [migrateEnhancementIndicatorAxes]. */
        fun placementFlagsForLegacyMode(mode: Int): Int = when (mode) {
            INDICATOR_MODE_BADGE, INDICATOR_MODE_BADGE_NUMERIC -> INDICATOR_PLACEMENT_LEFT
            INDICATOR_MODE_BOTH, INDICATOR_MODE_BOTH_NUMERIC ->
                INDICATOR_PLACEMENT_LEFT or INDICATOR_PLACEMENT_PAGE_NUMBER
            else -> INDICATOR_PLACEMENT_PAGE_NUMBER
        }

        /** Retired single-value placement → flags, used once by [migrateEnhancementIndicatorAxes]. */
        fun placementFlagsForLegacyPlacement(value: Int): Int = when (value) {
            LEGACY_PLACEMENT_LEFT -> INDICATOR_PLACEMENT_LEFT
            LEGACY_PLACEMENT_RIGHT -> INDICATOR_PLACEMENT_RIGHT
            LEGACY_PLACEMENT_ALL -> INDICATOR_PLACEMENT_LEFT or INDICATOR_PLACEMENT_PAGE_NUMBER
            else -> INDICATOR_PLACEMENT_PAGE_NUMBER
        }

        /** Legacy mode → visibility, used once by [migrateEnhancementIndicatorAxes]. */
        fun indicatorVisibleForLegacyMode(mode: Int): Boolean = mode != INDICATOR_MODE_HIDDEN

        /** Legacy mode → text style, used once by [migrateEnhancementIndicatorAxes]. */
        fun indicatorStyleForLegacyMode(mode: Int): Int =
            if (mode == INDICATOR_MODE_BADGE_NUMERIC || mode == INDICATOR_MODE_BOTH_NUMERIC) {
                INDICATOR_STYLE_NUMERIC
            } else {
                INDICATOR_STYLE_TEXT
            }

        /** Download-then-prewarm choices, see [prewarmAfterDownload]. */
        const val PREWARM_AFTER_DOWNLOAD_ASK = 0
        const val PREWARM_AFTER_DOWNLOAD_ALWAYS = 1
        const val PREWARM_AFTER_DOWNLOAD_NEVER = 2

        /** Performance usage indicator modes, see [performanceUsageMode]. */
        const val PERFORMANCE_USAGE_HIDDEN = 0

        /** Retired: used to mean "only while enhancing". Kept so stored values stay readable. */
        const val PERFORMANCE_USAGE_LEGACY_ENHANCING = 1
        const val PERFORMANCE_USAGE_ALWAYS = 2

        /** Whether [mode] asks for the panels at all; every non-hidden value counts as shown. */
        fun showsPerformancePanels(mode: Int): Boolean = mode != PERFORMANCE_USAGE_HIDDEN

        /** Enhancement badge text sizes, see [enhancementBadgeSize]. */
        const val BADGE_SIZE_SMALL = 0
        const val BADGE_SIZE_MEDIUM = 1
        const val BADGE_SIZE_LARGE = 2

        /** Enhancement badge font weights, see [enhancementBadgeWeight]. */
        const val BADGE_WEIGHT_NORMAL = 0
        const val BADGE_WEIGHT_MEDIUM = 1
        const val BADGE_WEIGHT_BOLD = 2

        /**
         * Badge offsets from the chosen corner, in dp, see [enhancementBadgeOffsetX].
         *
         * Horizontal is measured from the badge's own side, so the default keeps the same visual
         * inset in either corner. Vertical 0 puts its bottom edge on the page number's line.
         * Both defaults must stay inside [BADGE_OFFSET_MIN]..[BADGE_OFFSET_MAX].
         */
        const val BADGE_OFFSET_X_DEFAULT = 40
        const val BADGE_OFFSET_Y_DEFAULT = 0
        const val BADGE_OFFSET_MIN = 0
        const val BADGE_OFFSET_MAX = 120

        /** Slider granularity of the two offsets, in dp. */
        const val BADGE_OFFSET_STEP = 2

        /** Discrete positions the offset sliders expose, as Material3's `steps` argument. */
        const val BADGE_OFFSET_STEPS = (BADGE_OFFSET_MAX - BADGE_OFFSET_MIN) / BADGE_OFFSET_STEP - 1

        /**
         * Keeps a stored offset inside the slider's range.
         *
         * Stored values outlive the build that wrote them, so a reading is clamped instead of
         * trusted: an offset beyond the range would push the badge off screen with no way back.
         */
        fun clampBadgeOffset(value: Int): Int = value.coerceIn(BADGE_OFFSET_MIN, BADGE_OFFSET_MAX)

        /** Performance overlay corners, see [performanceUsagePlacement]. */
        const val PERFORMANCE_CORNER_LEFT = 0
        const val PERFORMANCE_CORNER_RIGHT = 1

        /** Readings the performance overlay can draw, see [performanceUsageItems]. */
        const val PERFORMANCE_ITEM_CPU = 1 shl 0
        const val PERFORMANCE_ITEM_POWER = 1 shl 1
        const val PERFORMANCE_ITEM_BATTERY = 1 shl 2

        /** At most this many readings share the overlay; the third one would only clutter it. */
        const val PERFORMANCE_ITEM_LIMIT = 2

        /** Retired three-way choice, only read by [migratePerformanceUsageItems]. */
        const val PERFORMANCE_USAGE_CONTENT_BOTH = 0
        const val PERFORMANCE_USAGE_CONTENT_CPU = 1
        const val PERFORMANCE_USAGE_CONTENT_POWER = 2

        fun showsCpuReading(items: Int): Boolean = items and PERFORMANCE_ITEM_CPU != 0

        fun showsPowerReading(items: Int): Boolean = items and PERFORMANCE_ITEM_POWER != 0

        fun showsBatteryReading(items: Int): Boolean = items and PERFORMANCE_ITEM_BATTERY != 0

        /**
         * Applies one tap on a reading chip; null means the tap would exceed [PERFORMANCE_ITEM_LIMIT]
         * and the caller has to say so instead of changing anything.
         */
        fun togglePerformanceItem(items: Int, item: Int): Int? {
            if (items and item != 0) return items xor item
            val next = items or item
            return if (Integer.bitCount(next) > PERFORMANCE_ITEM_LIMIT) null else next
        }

        /**
         * Corner the readings use: the one the enhancement status left free.
         *
         * While the status sits in a corner the choice is forced, so the two overlays can never
         * overlap; otherwise the user's own choice applies.
         */
        fun performanceCorner(
            enhancementVisible: Boolean,
            enhancementPlacement: Int,
            chosen: Int,
        ): Int = when {
            !enhancementVisible -> chosen
            drawsLeftBadge(enhancementPlacement) -> PERFORMANCE_CORNER_RIGHT
            drawsRightBadge(enhancementPlacement) -> PERFORMANCE_CORNER_LEFT
            else -> chosen
        }

        /** Retired content choice → item flags, used once by [migratePerformanceUsageItems]. */
        fun performanceItemsForLegacyContent(content: Int): Int = when (content) {
            PERFORMANCE_USAGE_CONTENT_CPU -> PERFORMANCE_ITEM_CPU
            PERFORMANCE_USAGE_CONTENT_POWER -> PERFORMANCE_ITEM_POWER
            else -> PERFORMANCE_ITEM_CPU or PERFORMANCE_ITEM_POWER
        }

        /** How much smaller two readings are drawn than one, in sp. */
        const val PERFORMANCE_TWO_ITEM_TEXT_DROP = 2f

        /** Floor for the shrunken pair, so the numbers stay legible. */
        const val PERFORMANCE_MIN_TEXT_SP = 9f

        /**
         * Text size (sp) of the readings overlay.
         *
         * One reading keeps the size the user picked, which is also what the status badge uses.
         * Two share a single row, so they are drawn slightly smaller to stay clear of the page
         * number.
         */
        fun performanceTextSizeSp(size: Int, readings: Int): Float {
            val base = when (size) {
                BADGE_SIZE_SMALL -> 11f
                BADGE_SIZE_LARGE -> 16f
                else -> 13f
            }
            return if (readings > 1) {
                (base - PERFORMANCE_TWO_ITEM_TEXT_DROP).coerceAtLeast(PERFORMANCE_MIN_TEXT_SP)
            } else {
                base
            }
        }

        /**
         * Maps the retired "badge visibility + page number marker" pair onto the four modes.
         *
         * The migration never turns off a toggle the user enabled: a visible badge stays visible and
         * a marker stays a marker. The only thing that cannot survive is the "only while enhancing"
         * condition, which the static model has no slot for - the two retired visibility values
         * therefore collapse onto the same inputs on purpose. Do not add a parameter to tell them
         * apart: that would tempt callers into silently dropping the marker.
         */
        fun indicatorModeForLegacySettings(badgeVisible: Boolean, marker: Boolean): Int = when {
            badgeVisible && marker -> INDICATOR_MODE_BOTH
            badgeVisible -> INDICATOR_MODE_BADGE
            marker -> INDICATOR_MODE_PAGE_NUMBER
            else -> INDICATOR_MODE_HIDDEN
        }

        /** Prewarm battery policy values, see [prewarmBatteryPolicy]. */
        const val BATTERY_POLICY_CHARGE_ONLY = 0
        const val BATTERY_POLICY_SUFFICIENT = 1
        const val BATTERY_POLICY_ANY_TIME = 2

        /** Read-old/write-new rule for the retired "only while charging" switch. */
        fun batteryPolicyForLegacyChargingRequirement(requireCharging: Boolean): Int =
            if (requireCharging) BATTERY_POLICY_CHARGE_ONLY else BATTERY_POLICY_ANY_TIME

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ImageScaleTypeWebGpuViewer = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }
    }
}
