package eu.kanade.presentation.reader.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QualityPresetTest {

    private fun values(
        model: Int = 0,
        noise: Int = 0,
        scale: Int = 2,
        maxSize: Int = 1600,
        tileSize: Int = 128,
    ) = QualityPreset.Values(model, noise, scale, maxSize, tileSize)

    @Test
    fun `stock values read as the balanced preset`() {
        QualityPreset.detect(values()) shouldBe QualityPreset.BALANCED
    }

    @Test
    fun `every preset detects itself`() {
        QualityPreset.entries.filter { it.values != null }.forEach { preset ->
            QualityPreset.detect(preset.values!!) shouldBe preset
        }
    }

    @Test
    fun `editing one value falls back to custom`() {
        val balanced = QualityPreset.BALANCED.values!!
        QualityPreset.detect(balanced.copy(tileSize = 256)) shouldBe QualityPreset.CUSTOM
        QualityPreset.detect(balanced.copy(noise = 2)) shouldBe QualityPreset.CUSTOM
        QualityPreset.detect(balanced.copy(maxSize = 2048)) shouldBe QualityPreset.CUSTOM
    }

    @Test
    fun `custom has no values to apply`() {
        QualityPreset.CUSTOM.values shouldBe null
    }

    @Test
    fun `presets survive the settings page fix-ups`() {
        // The quality page rewrites Pro denoise levels outside 0/3/4 and any scale but 2x, so a
        // preset using them would flip itself to Custom right after being applied.
        QualityPreset.entries.mapNotNull { it.values }.forEach { values ->
            values.scale shouldBe 2
            if (values.model == 1) (values.noise in setOf(0, 3, 4)) shouldBe true
        }
    }
}
