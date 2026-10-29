package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme

/**
 * Screen-anchored enhancement status for the current page. Mirrors [ReaderPageIndicator]'s
 * stroked-text look so both overlays read as one family.
 */
@Composable
fun ReaderEnhancementStatusIndicator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        color = Color(235, 235, 235),
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )
    val strokeStyle = style.copy(
        color = Color(45, 45, 45),
        drawStyle = Stroke(width = 4f),
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = strokeStyle,
        )
        Text(
            text = label,
            style = style,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderEnhancementStatusIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderEnhancementStatusIndicator(label = "Enhanced")
        }
    }
}
