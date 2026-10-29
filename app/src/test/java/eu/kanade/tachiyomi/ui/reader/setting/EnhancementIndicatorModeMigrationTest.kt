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
}
