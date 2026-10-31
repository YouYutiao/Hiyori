package eu.kanade.presentation.reader.settings

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

@Composable
internal fun ColumnScope.GeneralPage(viewModel: ReaderSettingsViewModel) {
    val readerTheme by viewModel.preferences.readerTheme.collectAsState()

    val flashPageState by viewModel.preferences.flashOnPageChange.collectAsState()

    val flashMillisPref = viewModel.preferences.flashDurationMillis
    val flashMillis by flashMillisPref.collectAsState()

    val flashIntervalPref = viewModel.preferences.flashPageInterval
    val flashInterval by flashIntervalPref.collectAsState()

    val flashColorPref = viewModel.preferences.flashColor
    val flashColor by flashColorPref.collectAsState()

    SettingsChipRow(MR.strings.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { viewModel.preferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    HeadingItem(stringResource(MR.strings.reader_section_screen_indicators))

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = viewModel.preferences.showPageNumber,
    )

    val showPageNumber by viewModel.preferences.showPageNumber.collectAsState()
    val indicatorVisible by viewModel.preferences.enhancementIndicatorVisible().collectAsState()
    val indicatorPlacement by viewModel.preferences.enhancementIndicatorPlacement().collectAsState()
    val indicatorStyle by viewModel.preferences.enhancementIndicatorStyle().collectAsState()

    // Three rows instead of one long list: whether it is drawn, where, and how it reads. Each row
    // only appears once the choice above it makes it meaningful.
    SettingsChipRow(MR.strings.reader_section_enhancement_indicator) {
        FilterChip(
            selected = !indicatorVisible,
            onClick = { viewModel.preferences.enhancementIndicatorVisible().set(false) },
            label = { Text(stringResource(MR.strings.pref_enhancement_indicator_hidden)) },
        )
        FilterChip(
            selected = indicatorVisible,
            onClick = { viewModel.preferences.enhancementIndicatorVisible().set(true) },
            label = { Text(stringResource(MR.strings.pref_enhancement_indicator_visible)) },
        )
    }

    if (indicatorVisible) {
        // Corners are mutually exclusive, the page number marker stacks on either of them, and the
        // last remaining placement cannot be switched off (the row above covers "nothing at all").
        val placementPref = viewModel.preferences.enhancementIndicatorPlacement()
        OptionChipRow {
            listOf(
                ReaderPreferences.INDICATOR_PLACEMENT_LEFT to MR.strings.pref_enhancement_indicator_left,
                ReaderPreferences.INDICATOR_PLACEMENT_RIGHT to MR.strings.pref_enhancement_indicator_right,
                ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER to
                    MR.strings.pref_enhancement_indicator_page_number,
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = indicatorPlacement and value != 0,
                    onClick = { placementPref.set(ReaderPreferences.togglePlacement(indicatorPlacement, value)) },
                    // The page number marker is meaningless while page numbers are off, so it is
                    // shown greyed out instead of silently doing nothing.
                    enabled = !value.needsPageNumber() || showPageNumber,
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        // Words or numbers only matter for a corner badge; the page number keeps its own typography.
        if (ReaderPreferences.drawsBadge(indicatorPlacement)) {
            OptionChipRow {
                listOf(
                    ReaderPreferences.INDICATOR_STYLE_TEXT to MR.strings.pref_enhancement_indicator_style_text,
                    ReaderPreferences.INDICATOR_STYLE_NUMERIC to
                        MR.strings.pref_enhancement_indicator_style_numeric,
                ).forEach { (value, labelRes) ->
                    FilterChip(
                        selected = indicatorStyle == value,
                        onClick = { viewModel.preferences.enhancementIndicatorStyle().set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            val badgeSize by viewModel.preferences.enhancementBadgeSize().collectAsState()
            val badgeWeight by viewModel.preferences.enhancementBadgeWeight().collectAsState()
            SettingsChipRow(MR.strings.reader_badge_size) {
                listOf(
                    ReaderPreferences.BADGE_SIZE_SMALL to MR.strings.reader_badge_size_small,
                    ReaderPreferences.BADGE_SIZE_MEDIUM to MR.strings.reader_badge_size_medium,
                    ReaderPreferences.BADGE_SIZE_LARGE to MR.strings.reader_badge_size_large,
                ).forEach { (value, labelRes) ->
                    FilterChip(
                        selected = badgeSize == value,
                        onClick = { viewModel.preferences.enhancementBadgeSize().set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            SettingsChipRow(MR.strings.reader_badge_weight) {
                listOf(
                    ReaderPreferences.BADGE_WEIGHT_NORMAL to MR.strings.reader_badge_weight_normal,
                    ReaderPreferences.BADGE_WEIGHT_MEDIUM to MR.strings.reader_badge_weight_medium,
                    ReaderPreferences.BADGE_WEIGHT_BOLD to MR.strings.reader_badge_weight_bold,
                ).forEach { (value, labelRes) ->
                    FilterChip(
                        selected = badgeWeight == value,
                        onClick = { viewModel.preferences.enhancementBadgeWeight().set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            val badgeOffsetX by viewModel.preferences.enhancementBadgeOffsetX().collectAsState()
            val badgeOffsetY by viewModel.preferences.enhancementBadgeOffsetY().collectAsState()
            val badgeOffsetXPref = viewModel.preferences.enhancementBadgeOffsetX()
            val badgeOffsetYPref = viewModel.preferences.enhancementBadgeOffsetY()

            // Both offsets are measured from the badge's own corner, so the same value keeps the
            // same inset in the left and right corner.
            SliderItem(
                value = badgeOffsetX,
                valueRange = ReaderPreferences.BADGE_OFFSET_MIN..ReaderPreferences.BADGE_OFFSET_MAX,
                label = stringResource(MR.strings.reader_badge_offset_x),
                onChange = { badgeOffsetXPref.set(it) },
                steps = ReaderPreferences.BADGE_OFFSET_STEPS,
                valueString = stringResource(MR.strings.reader_badge_offset_value, badgeOffsetX),
            )
            SliderItem(
                value = badgeOffsetY,
                valueRange = ReaderPreferences.BADGE_OFFSET_MIN..ReaderPreferences.BADGE_OFFSET_MAX,
                label = stringResource(MR.strings.reader_badge_offset_y),
                onChange = { badgeOffsetYPref.set(it) },
                steps = ReaderPreferences.BADGE_OFFSET_STEPS,
                valueString = stringResource(MR.strings.reader_badge_offset_value, badgeOffsetY),
            )
            if (badgeOffsetX != ReaderPreferences.BADGE_OFFSET_X_DEFAULT ||
                badgeOffsetY != ReaderPreferences.BADGE_OFFSET_Y_DEFAULT
            ) {
                Text(
                    text = stringResource(MR.strings.reader_badge_position_reset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            badgeOffsetXPref.set(ReaderPreferences.BADGE_OFFSET_X_DEFAULT)
                            badgeOffsetYPref.set(ReaderPreferences.BADGE_OFFSET_Y_DEFAULT)
                        }
                        .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
                )
            }
        }
    }

    // Only relevant while the indicator is drawn: with everything hidden there is no marker to
    // turn page numbers on for.
    if (indicatorVisible && !showPageNumber) {
        // Tappable so the user does not have to find the page number toggle and come back.
        Text(
            text = stringResource(MR.strings.pref_enhancement_indicator_needs_page_number),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.preferences.showPageNumber.set(true) }
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        )
    }

    val performanceUsageMode by viewModel.preferences.performanceUsageMode().collectAsState()
    val performanceUsagePlacement by viewModel.preferences.performanceUsagePlacement().collectAsState()
    val performanceUsageItems by viewModel.preferences.performanceUsageItems().collectAsState()
    val performanceItemsPref = viewModel.preferences.performanceUsageItems()
    val context = LocalContext.current

    // Same three-row shape as the status display: whether, where, and what it reads.
    SettingsChipRow(MR.strings.reader_section_performance_usage) {
        FilterChip(
            selected = performanceUsageMode == ReaderPreferences.PERFORMANCE_USAGE_HIDDEN,
            onClick = { viewModel.preferences.performanceUsageMode().set(ReaderPreferences.PERFORMANCE_USAGE_HIDDEN) },
            label = { Text(stringResource(MR.strings.pref_performance_usage_hidden)) },
        )
        FilterChip(
            selected = ReaderPreferences.showsPerformancePanels(performanceUsageMode),
            onClick = { viewModel.preferences.performanceUsageMode().set(ReaderPreferences.PERFORMANCE_USAGE_ALWAYS) },
            label = { Text(stringResource(MR.strings.pref_performance_usage_always)) },
        )
    }
    if (ReaderPreferences.showsPerformancePanels(performanceUsageMode)) {
        // The corner the status indicator occupies is off limits: the readings are forced into the
        // other one and the choice is disabled until that corner is free again.
        val cornerLocked = indicatorVisible && ReaderPreferences.drawsBadge(indicatorPlacement)
        val performanceCorner = ReaderPreferences.performanceCorner(
            enhancementVisible = indicatorVisible,
            enhancementPlacement = indicatorPlacement,
            chosen = performanceUsagePlacement,
        )
        OptionChipRow {
            listOf(
                ReaderPreferences.PERFORMANCE_CORNER_LEFT to MR.strings.pref_enhancement_indicator_left,
                ReaderPreferences.PERFORMANCE_CORNER_RIGHT to MR.strings.pref_enhancement_indicator_right,
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = performanceCorner == value,
                    onClick = { viewModel.preferences.performanceUsagePlacement().set(value) },
                    enabled = !cornerLocked,
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        // A third reading would not fit the corner, so it is refused with a short message instead
        // of silently dropping one of the two the user already picked.
        val limitMessage = stringResource(MR.strings.reader_performance_items_limit)
        OptionChipRow {
            listOf(
                ReaderPreferences.PERFORMANCE_ITEM_CPU to MR.strings.pref_performance_item_cpu,
                ReaderPreferences.PERFORMANCE_ITEM_POWER to MR.strings.pref_performance_item_power,
                ReaderPreferences.PERFORMANCE_ITEM_BATTERY to MR.strings.pref_performance_item_battery,
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = performanceUsageItems and value != 0,
                    onClick = {
                        val next = ReaderPreferences.togglePerformanceItem(performanceUsageItems, value)
                        if (next == null) {
                            Toast.makeText(context, limitMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            performanceItemsPref.set(next)
                        }
                    },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        // The readings get the same customization as the status badge: size, weight and the two
        // offsets from their own corner.
        val performanceSize by viewModel.preferences.performanceUsageSize().collectAsState()
        val performanceWeight by viewModel.preferences.performanceUsageWeight().collectAsState()
        SettingsChipRow(MR.strings.reader_performance_size) {
            listOf(
                ReaderPreferences.BADGE_SIZE_SMALL to MR.strings.reader_badge_size_small,
                ReaderPreferences.BADGE_SIZE_MEDIUM to MR.strings.reader_badge_size_medium,
                ReaderPreferences.BADGE_SIZE_LARGE to MR.strings.reader_badge_size_large,
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = performanceSize == value,
                    onClick = { viewModel.preferences.performanceUsageSize().set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        SettingsChipRow(MR.strings.reader_performance_weight) {
            listOf(
                ReaderPreferences.BADGE_WEIGHT_NORMAL to MR.strings.reader_badge_weight_normal,
                ReaderPreferences.BADGE_WEIGHT_MEDIUM to MR.strings.reader_badge_weight_medium,
                ReaderPreferences.BADGE_WEIGHT_BOLD to MR.strings.reader_badge_weight_bold,
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = performanceWeight == value,
                    onClick = { viewModel.preferences.performanceUsageWeight().set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        val performanceOffsetX by viewModel.preferences.performanceUsageOffsetX().collectAsState()
        val performanceOffsetY by viewModel.preferences.performanceUsageOffsetY().collectAsState()
        val performanceOffsetXPref = viewModel.preferences.performanceUsageOffsetX()
        val performanceOffsetYPref = viewModel.preferences.performanceUsageOffsetY()
        SliderItem(
            value = performanceOffsetX,
            valueRange = ReaderPreferences.BADGE_OFFSET_MIN..ReaderPreferences.BADGE_OFFSET_MAX,
            label = stringResource(MR.strings.reader_performance_offset_x),
            onChange = { performanceOffsetXPref.set(it) },
            steps = ReaderPreferences.BADGE_OFFSET_STEPS,
            valueString = stringResource(MR.strings.reader_badge_offset_value, performanceOffsetX),
        )
        SliderItem(
            value = performanceOffsetY,
            valueRange = ReaderPreferences.BADGE_OFFSET_MIN..ReaderPreferences.BADGE_OFFSET_MAX,
            label = stringResource(MR.strings.reader_performance_offset_y),
            onChange = { performanceOffsetYPref.set(it) },
            steps = ReaderPreferences.BADGE_OFFSET_STEPS,
            valueString = stringResource(MR.strings.reader_badge_offset_value, performanceOffsetY),
        )
        if (performanceOffsetX != ReaderPreferences.BADGE_OFFSET_X_DEFAULT ||
            performanceOffsetY != ReaderPreferences.BADGE_OFFSET_Y_DEFAULT
        ) {
            Text(
                text = stringResource(MR.strings.reader_badge_position_reset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        performanceOffsetXPref.set(ReaderPreferences.BADGE_OFFSET_X_DEFAULT)
                        performanceOffsetYPref.set(ReaderPreferences.BADGE_OFFSET_Y_DEFAULT)
                    }
                    .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
            )
        }
    }

    val verticalNavigatorModes by viewModel.preferences.verticalNavigator.collectAsState()

    SettingsChipRow(MR.strings.pref_vertical_navigator) {
        ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.forEach { mode ->
            FilterChip(
                selected = verticalNavigatorModes.contains(mode),
                onClick = {
                    val newModes = if (verticalNavigatorModes.contains(mode)) {
                        verticalNavigatorModes - mode
                    } else {
                        verticalNavigatorModes + mode
                    }
                    viewModel.preferences.verticalNavigator.set(newModes)
                },
                label = { Text(stringResource(mode.stringRes)) },
            )
        }
    }

    if (verticalNavigatorModes.isNotEmpty()) {
        val verticalNavigatorHeightPref = viewModel.preferences.verticalNavigatorHeight
        val verticalNavigatorHeight by verticalNavigatorHeightPref.collectAsState()

        CheckboxItem(
            label = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
            pref = viewModel.preferences.verticalNavigatorOnLeft,
        )

        SliderItem(
            label = stringResource(MR.strings.pref_vertical_navigator_height),
            value = verticalNavigatorHeight,
            valueRange = 65..100,
            steps = 6,
            onChange = { verticalNavigatorHeightPref.set(it) },
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = viewModel.preferences.fullscreen,
    )

    val isFullscreen by viewModel.preferences.fullscreen.collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = viewModel.preferences.drawUnderCutout,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = viewModel.preferences.keepScreenOn,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_long_tap),
        pref = viewModel.preferences.readWithLongTap,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = viewModel.preferences.alwaysShowChapterTransition,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = viewModel.preferences.pageTransitions,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_page),
        pref = viewModel.preferences.flashOnPageChange,
    )
    if (flashPageState) {
        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(MR.strings.pref_flash_duration),
            valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = {
                flashIntervalPref.set(it)
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SettingsChipRow(MR.strings.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

/**
 * Chip row without a title.
 *
 * The follow-up choices of both overlays belong to the section heading above them (增强状态显示 /
 * 性能占用显示); giving each row its own heading made two identical-looking blocks that were hard
 * to scan. Only the customization rows keep their prefixed titles.
 */
@Composable
private fun OptionChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.padding(
            start = SettingsItemsPaddings.Horizontal,
            end = SettingsItemsPaddings.Horizontal,
            bottom = SettingsItemsPaddings.Vertical,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Whether the option draws the page number marker, and therefore needs page numbers to be shown. */
private fun Int.needsPageNumber(): Boolean = this == ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER
