package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Pins the badge position contract: the badge is anchored to the chosen corner and the two offsets
 * only nudge it from there.
 *
 * The defaults are part of that contract, not free numbers:
 * - horizontal > 0, because a badge sitting on the edge lands inside the screen's rounded corner
 *   once it is as low as the page number;
 * - vertical == 0, because "same height as the page number" is exactly the anchor.
 */
class EnhancementBadgeOffsetTest {

    @Test
    fun `default offsets keep the badge off the edge and on the page number's line`() {
        ReaderPreferences.BADGE_OFFSET_Y_DEFAULT shouldBe 0
        ReaderPreferences.BADGE_OFFSET_X_DEFAULT shouldNotBe 0
    }

    @Test
    fun `both defaults sit inside the adjustable range`() {
        ReaderPreferences.clampBadgeOffset(ReaderPreferences.BADGE_OFFSET_X_DEFAULT) shouldBe
            ReaderPreferences.BADGE_OFFSET_X_DEFAULT
        ReaderPreferences.clampBadgeOffset(ReaderPreferences.BADGE_OFFSET_Y_DEFAULT) shouldBe
            ReaderPreferences.BADGE_OFFSET_Y_DEFAULT
    }

    @Test
    fun `stored offsets outside the range are clamped instead of trusted`() {
        ReaderPreferences.clampBadgeOffset(-40) shouldBe ReaderPreferences.BADGE_OFFSET_MIN
        ReaderPreferences.clampBadgeOffset(ReaderPreferences.BADGE_OFFSET_MAX + 500) shouldBe
            ReaderPreferences.BADGE_OFFSET_MAX
        ReaderPreferences.clampBadgeOffset(64) shouldBe 64
    }

    @Test
    fun `slider steps land exactly on the maximum at the chosen granularity`() {
        // Material's `steps` counts the values between the endpoints, so the slider can only reach
        // the maximum when (max - min) is a whole number of steps.
        val intervals = ReaderPreferences.BADGE_OFFSET_STEPS + 1
        (ReaderPreferences.BADGE_OFFSET_MAX - ReaderPreferences.BADGE_OFFSET_MIN) % intervals shouldBe 0
        (ReaderPreferences.BADGE_OFFSET_MAX - ReaderPreferences.BADGE_OFFSET_MIN) / intervals shouldBe
            ReaderPreferences.BADGE_OFFSET_STEP
    }
}
