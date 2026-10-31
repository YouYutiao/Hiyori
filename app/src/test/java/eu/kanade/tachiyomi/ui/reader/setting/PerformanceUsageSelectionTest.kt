package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the two rules the performance overlay shares with the status indicator: at most two
 * readings, and the corner the status indicator is not using.
 */
class PerformanceUsageSelectionTest {

    private val cpu = ReaderPreferences.PERFORMANCE_ITEM_CPU
    private val power = ReaderPreferences.PERFORMANCE_ITEM_POWER
    private val battery = ReaderPreferences.PERFORMANCE_ITEM_BATTERY

    @Test
    fun `a second reading fits`() {
        ReaderPreferences.togglePerformanceItem(cpu, power) shouldBe (cpu or power)
    }

    @Test
    fun `a third reading is refused instead of dropping one`() {
        ReaderPreferences.togglePerformanceItem(cpu or power, battery) shouldBe null
    }

    @Test
    fun `turning one off frees the slot again`() {
        val single = ReaderPreferences.togglePerformanceItem(cpu or power, cpu)
        single shouldBe power
        ReaderPreferences.togglePerformanceItem(single!!, battery) shouldBe (power or battery)
    }

    @Test
    fun `readings are recognised from the flags`() {
        val items = cpu or battery
        ReaderPreferences.showsCpuReading(items) shouldBe true
        ReaderPreferences.showsPowerReading(items) shouldBe false
        ReaderPreferences.showsBatteryReading(items) shouldBe true
    }

    @Test
    fun `the readings take the corner the status indicator left free`() {
        val left = ReaderPreferences.INDICATOR_PLACEMENT_LEFT
        val right = ReaderPreferences.INDICATOR_PLACEMENT_RIGHT
        ReaderPreferences.performanceCorner(
            enhancementVisible = true,
            enhancementPlacement = left,
            chosen = ReaderPreferences.PERFORMANCE_CORNER_LEFT,
        ) shouldBe ReaderPreferences.PERFORMANCE_CORNER_RIGHT
        ReaderPreferences.performanceCorner(
            enhancementVisible = true,
            enhancementPlacement = right,
            chosen = ReaderPreferences.PERFORMANCE_CORNER_RIGHT,
        ) shouldBe ReaderPreferences.PERFORMANCE_CORNER_LEFT
    }

    @Test
    fun `a free corner keeps the user's own choice`() {
        val pageOnly = ReaderPreferences.INDICATOR_PLACEMENT_PAGE_NUMBER
        ReaderPreferences.performanceCorner(
            enhancementVisible = true,
            enhancementPlacement = pageOnly,
            chosen = ReaderPreferences.PERFORMANCE_CORNER_LEFT,
        ) shouldBe ReaderPreferences.PERFORMANCE_CORNER_LEFT
        ReaderPreferences.performanceCorner(
            enhancementVisible = false,
            enhancementPlacement = pageOnly,
            chosen = ReaderPreferences.PERFORMANCE_CORNER_RIGHT,
        ) shouldBe ReaderPreferences.PERFORMANCE_CORNER_RIGHT
    }

    @Test
    fun `a single reading keeps the size the user picked`() {
        ReaderPreferences.performanceTextSizeSp(ReaderPreferences.BADGE_SIZE_SMALL, readings = 1) shouldBe 11f
        ReaderPreferences.performanceTextSizeSp(ReaderPreferences.BADGE_SIZE_MEDIUM, readings = 1) shouldBe 13f
        ReaderPreferences.performanceTextSizeSp(ReaderPreferences.BADGE_SIZE_LARGE, readings = 1) shouldBe 16f
    }

    @Test
    fun `two readings sharing a row are drawn smaller`() {
        ReaderPreferences.performanceTextSizeSp(ReaderPreferences.BADGE_SIZE_MEDIUM, readings = 2) shouldBe 11f
        ReaderPreferences.performanceTextSizeSp(ReaderPreferences.BADGE_SIZE_LARGE, readings = 2) shouldBe 14f
        // The pair never drops below the legibility floor.
        ReaderPreferences.performanceTextSizeSp(ReaderPreferences.BADGE_SIZE_SMALL, readings = 2) shouldBe 9f
    }

    @Test
    fun `the retired content choice maps onto the item flags`() {
        ReaderPreferences.performanceItemsForLegacyContent(
            ReaderPreferences.PERFORMANCE_USAGE_CONTENT_BOTH,
        ) shouldBe (cpu or power)
        ReaderPreferences.performanceItemsForLegacyContent(
            ReaderPreferences.PERFORMANCE_USAGE_CONTENT_CPU,
        ) shouldBe cpu
        ReaderPreferences.performanceItemsForLegacyContent(
            ReaderPreferences.PERFORMANCE_USAGE_CONTENT_POWER,
        ) shouldBe power
    }
}
