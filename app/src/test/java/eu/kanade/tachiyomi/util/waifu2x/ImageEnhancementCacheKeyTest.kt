package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cache key is how "this page was enhanced with the guard" is expressed. Nothing asserted it
 * before, so a release could change what a cached page means and silently keep serving the old one.
 */
class ImageEnhancementCacheKeyTest {

    // Model ids are plain integers in the preference; only Real-ESRGAN has a named constant.
    private val modelSe = 0
    private val modelPro = 1

    private fun hash(
        model: Int,
        noise: Int = 0,
        tileSize: Int = 128,
        realEsrganStyle: Int = Waifu2x.REAL_ESRGAN_STYLE_ANIME,
    ): String = ImageEnhancementCache.getConfigHash(
        noise = noise,
        scale = 2,
        model = model,
        realEsrganStyle = realEsrganStyle,
        maxWidth = 1600,
        maxHeight = 1600,
        tileSize = tileSize,
        processingBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
    )

    @Test
    fun `every Real CUGAN noise level carries the guard revision`() {
        // SE used to be excluded, which is why pages enhanced before the guard kept their blocky
        // output after the update.
        listOf(0, 1, 2, 3, 4).forEach { noise ->
            val key = hash(model = modelSe, noise = noise)
            assertTrue(key.contains("_tv4"), "SE noise=$noise key was $key")
        }
        listOf(0, 3, 4).forEach { noise ->
            val key = hash(model = modelPro, noise = noise)
            assertTrue(key.contains("_tv4"), "Pro noise=$noise key was $key")
        }
    }

    @Test
    fun `models that never run the guard have no guard suffix`() {
        val key = hash(model = Waifu2x.MODEL_REAL_ESRGAN_ANIME)
        assertTrue(!key.contains("_tv"), "Real-ESRGAN key was $key")
    }

    @Test
    fun `the two Real CUGAN models do not share a key`() {
        assertNotEquals(
            hash(model = modelSe, noise = 0),
            hash(model = modelPro, noise = 0),
        )
    }

    @Test
    fun `tile size is part of the key`() {
        assertNotEquals(
            hash(model = modelSe, tileSize = 96),
            hash(model = modelSe, tileSize = 192),
        )
    }

    @Test
    fun `the guard revision is a single stable token`() {
        val key = hash(model = modelSe)
        assertEquals(1, Regex("_tv\\d+").findAll(key).count(), "key was $key")
    }
}
