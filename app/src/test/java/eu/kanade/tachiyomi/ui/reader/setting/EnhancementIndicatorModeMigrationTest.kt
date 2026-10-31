package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the legacy "badge visibility + page number marker" migration mapping (approach §3.4) so it
 * can never regress into silently dropping a toggle the user enabled.
 *
 * The retired badge values "only while enhancing" and "always" both collapse onto
 * `badgeVisible = true` on purpose: the static four-mode model has no slot for the temporal
 * condition, so the only loss is that condition itself - the badge and the marker must both
 * survive whenever the user had them on.
 */
class EnhancementIndicatorModeMigrationTest {

    @Test
    fun `no badge and no marker maps to hidden`() {
        ReaderPreferences.indicatorModeForLegacySettings(badgeVisible = false, marker = false) shouldBe
            ReaderPreferences.INDICATOR_MODE_HIDDEN
    }

    @Test
    fun `marker only keeps the page number star`() {
        ReaderPreferences.indicatorModeForLegacySettings(badgeVisible = false, marker = true) shouldBe
            ReaderPreferences.INDICATOR_MODE_PAGE_NUMBER
    }

    @Test
    fun `badge plus marker keeps both indicators`() {
        // Covers both retired values "only while enhancing" and "always" with the marker on:
        // they collapse onto the same input by design.
        ReaderPreferences.indicatorModeForLegacySettings(badgeVisible = true, marker = true) shouldBe
            ReaderPreferences.INDICATOR_MODE_BOTH
    }

    @Test
    fun `badge without marker keeps the badge and never drops the marker the user did not have`() {
        ReaderPreferences.indicatorModeForLegacySettings(badgeVisible = true, marker = false) shouldBe
            ReaderPreferences.INDICATOR_MODE_BADGE
    }

    @Test
    fun `hidden stays hidden when split into the three choices`() {
        ReaderPreferences.indicatorVisibleForLegacyMode(ReaderPreferences.INDICATOR_MODE_HIDDEN) shouldBe false
    }

    @Test
    fun `every visible legacy mode comes back visible`() {
        val visibleModes = listOf(
            ReaderPreferences.INDICATOR_MODE_BADGE,
            ReaderPreferences.INDICATOR_MODE_BADGE_NUMERIC,
            ReaderPreferences.INDICATOR_MODE_PAGE_NUMBER,
            ReaderPreferences.INDICATOR_MODE_BOTH,
            ReaderPreferences.INDICATOR_MODE_BOTH_NUMERIC,
        )
        visibleModes.forEach {
            ReaderPreferences.indicatorVisibleForLegacyMode(it) shouldBe true
        }
    }

    @Test
    fun `corner modes map to the left corner, page number modes to the marker`() {
        ReaderPreferences.placementFlagsForLegacyMode(ReaderPreferences.INDICATOR_MODE_BADGE) shouldBe
            ReaderPreferences.INDICATOR_PLACEMENT_LEFT
        ReaderPreferences.placementFlagsForLegacyMode(ReaderPreferences.INDICATOR_MODE_PAGE_NUMBER) shouldBe
            ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER
    }

    @Test
    fun `a legacy badge plus marker user keeps the marker next to the badge`() {
        // "都显示" used to mean badge + marker, so it becomes the left corner plus the marker.
        val expected = ReaderPreferences.INDICATOR_PLACEMENT_LEFT or
            ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER
        ReaderPreferences.placementFlagsForLegacyMode(ReaderPreferences.INDICATOR_MODE_BOTH) shouldBe expected
        ReaderPreferences.placementFlagsForLegacyMode(
            ReaderPreferences.INDICATOR_MODE_BOTH_NUMERIC,
        ) shouldBe expected
    }

    @Test
    fun `a build with the single-value placement translates it without touching the other two`() {
        ReaderPreferences.placementFlagsForLegacyPlacement(ReaderPreferences.LEGACY_PLACEMENT_LEFT) shouldBe
            ReaderPreferences.INDICATOR_PLACEMENT_LEFT
        ReaderPreferences.placementFlagsForLegacyPlacement(ReaderPreferences.LEGACY_PLACEMENT_RIGHT) shouldBe
            ReaderPreferences.INDICATOR_PLACEMENT_RIGHT
        ReaderPreferences.placementFlagsForLegacyPlacement(
            ReaderPreferences.LEGACY_PLACEMENT_PAGE_NUMBER,
        ) shouldBe ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER
        // "All" is impossible now that the corners exclude each other: keep the badge and the marker.
        ReaderPreferences.placementFlagsForLegacyPlacement(ReaderPreferences.LEGACY_PLACEMENT_ALL) shouldBe
            (ReaderPreferences.INDICATOR_PLACEMENT_LEFT or ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER)
    }

    @Test
    fun `numeric legacy modes keep the numeric style`() {
        ReaderPreferences.indicatorStyleForLegacyMode(ReaderPreferences.INDICATOR_MODE_BADGE_NUMERIC) shouldBe
            ReaderPreferences.INDICATOR_STYLE_NUMERIC
        ReaderPreferences.indicatorStyleForLegacyMode(ReaderPreferences.INDICATOR_MODE_BOTH_NUMERIC) shouldBe
            ReaderPreferences.INDICATOR_STYLE_NUMERIC
        ReaderPreferences.indicatorStyleForLegacyMode(ReaderPreferences.INDICATOR_MODE_BOTH) shouldBe
            ReaderPreferences.INDICATOR_STYLE_TEXT
        ReaderPreferences.indicatorStyleForLegacyMode(ReaderPreferences.INDICATOR_MODE_HIDDEN) shouldBe
            ReaderPreferences.INDICATOR_STYLE_TEXT
    }
}
