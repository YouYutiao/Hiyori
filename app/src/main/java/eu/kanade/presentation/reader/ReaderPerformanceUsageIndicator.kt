package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme

/**
 * Performance readings of the moment, drawn as the same outlined text as the enhancement status.
 *
 * The earlier plates (background, bar, value) are gone: one typography for both overlays is what
 * makes the two corners read as one family. Up to two readings share a single row, left to right in
 * the order the settings list them, and hug the side of the corner they sit in.
 */
@Composable
fun ReaderPerformanceUsageIndicator(
    readings: List<String>,
    fontSize: TextUnit = MaterialTheme.typography.bodySmall.fontSize,
    fontWeight: FontWeight = FontWeight.Medium,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(READINGS_GAP),
    ) {
        readings.forEach { reading ->
            ReaderEnhancementStatusIndicator(
                label = reading,
                fontSize = fontSize,
                fontWeight = fontWeight,
            )
        }
    }
}

/** Gap between the two readings; small enough that the pair still reads as one block. */
private val READINGS_GAP = 6.dp

@PreviewLightDark
@Composable
private fun ReaderPerformanceUsageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPerformanceUsageIndicator(
                readings = listOf("CPU 62%", "功耗 3.2 W"),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun ReaderPerformanceUsageIndicatorSinglePreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPerformanceUsageIndicator(readings = listOf("CPU 62%"))
        }
    }
}
