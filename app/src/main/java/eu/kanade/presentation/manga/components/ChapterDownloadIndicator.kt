package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.data.download.model.Download
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.DownloadArrow
import mihon.icons.custommaterialsymbols.rounded.DownloadCircle
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.RocketLaunch
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import kotlin.math.ceil

enum class ChapterDownloadAction {
    START,
    START_NOW,
    CANCEL,
    DELETE,

    /** Queue a downloaded chapter for page enhancement. */
    PREWARM,

    /** Jump a queued chapter to the front and make sure the queue is running. */
    PREWARM_NOW,

    /** Drop a chapter from the queue without touching what is already cached. */
    PREWARM_CANCEL,
}

@Composable
fun ChapterDownloadIndicator(
    enabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    prewarmProgressProvider: () -> Int? = { null },
    prewarmed: Boolean = false,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val downloadState = downloadStateProvider()) {
        Download.State.NOT_DOWNLOADED -> NotDownloadedIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
        Download.State.QUEUE, Download.State.DOWNLOADING, Download.State.UPLOADING -> DownloadingIndicator(
            enabled = enabled,
            modifier = modifier,
            downloadState = downloadState,
            downloadProgressProvider = downloadProgressProvider,
            onClick = onClick,
        )
        // A chapter that is fully downloaded but whose pages are still being enhanced borrows the
        // same slot: the download icon has nothing left to say, and a second badge next to it made
        // the row read as two conflicting states.
        Download.State.DOWNLOADED -> prewarmProgressProvider()?.let { progress ->
            PrewarmingIndicator(progress = progress, onClick = onClick, modifier = modifier)
        } ?: DownloadedIndicator(
            enabled = enabled,
            prewarmed = prewarmed,
            modifier = modifier,
            onClick = onClick,
        )
        Download.State.ERROR -> ErrorIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
    }
}

@Composable
private fun NotDownloadedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START_NOW) },
                onClick = { onClick(ChapterDownloadAction.START) },
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CustomMaterialSymbols.Rounded.DownloadCircle,
            contentDescription = stringResource(MR.strings.manga_download),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadingIndicator(
    enabled: Boolean,
    downloadState: Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.CANCEL) },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        val trackColor = color.copy(alpha = TRACK_ALPHA)
        val stroke = Stroke(width = with(LocalDensity.current) { IndicatorStrokeWidth.toPx() }, cap = StrokeCap.Round)
        val downloadProgress = downloadProgressProvider()
        val indeterminate = downloadState == Download.State.QUEUE ||
            (
                (downloadState == Download.State.DOWNLOADING || downloadState == Download.State.UPLOADING) &&
                    downloadProgress == 0
                )
        if (indeterminate) {
            CircularWavyProgressIndicator(
                modifier = IndicatorModifier,
                color = color,
                trackColor = Color.Transparent,
                stroke = stroke,
                trackStroke = stroke,
                // Only an active download waves, so a queued one reads as waiting
                amplitude = if (downloadState == Download.State.DOWNLOADING) INDICATOR_AMPLITUDE else 0f,
                wavelength = IndicatorWavelength,
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress / 100f,
                animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "progress",
            )
            CircularWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = IndicatorModifier,
                color = color,
                trackColor = trackColor,
                stroke = stroke,
                trackStroke = stroke,
                amplitude = { WavyProgressIndicatorDefaults.indicatorAmplitude(it) * INDICATOR_AMPLITUDE },
                wavelength = IndicatorWavelength,
            )
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_start_downloading_now)) },
                onClick = {
                    onClick(ChapterDownloadAction.START_NOW)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterDownloadAction.CANCEL)
                    isMenuExpanded = false
                },
            )
        }
        Icon(
            imageVector = CustomMaterialSymbols.Rounded.DownloadArrow,
            contentDescription = null,
            modifier = ArrowModifier,
            tint = color,
        )
    }
}

@Composable
private fun DownloadedIndicator(
    enabled: Boolean,
    prewarmed: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            // A warmed chapter has nothing left to prewarm, and offering "cancel" next to "delete"
            // read as two ways of undoing the same thing; it only keeps the delete entry.
            if (!prewarmed) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.prewarm_chapter_start)) },
                    onClick = {
                        onClick(ChapterDownloadAction.PREWARM)
                        isMenuExpanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterDownloadAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

/**
 * Marks a downloaded chapter that is being enhanced right now.
 *
 * Deliberately unlike the download ring: a segmented gauge (tick per slice, filled as pages finish)
 * with a small rocket in the middle. The download ring is a smooth wave, so "still downloading" and
 * "downloaded, now enhancing" never read alike; the rocket is kept smaller than the ring so the two
 * do not touch even at the smallest row height.
 */
@Composable
private fun PrewarmingIndicator(
    progress: Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress / 100f,
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "prewarm-progress",
    )
    val color = MaterialTheme.colorScheme.primary
    val trackColor = color.copy(alpha = TRACK_ALPHA)
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = true,
                hapticFeedback = LocalHapticFeedback.current,
                // Long press is the queue's own "run it now"; the menu spells it out for people who
                // never try long presses.
                onLongClick = { onClick(ChapterDownloadAction.PREWARM_NOW) },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = PrewarmIndicatorModifier) {
            val stroke = PrewarmStrokeWidth.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val slice = 360f / PREWARM_SEGMENTS
            // A whole segment lights up as soon as its share of the progress is reached, so the
            // gauge steps page by page instead of creeping; rounding up keeps the first page
            // visible right away.
            val filled = ceil(animatedProgress.coerceIn(0f, 1f) * PREWARM_SEGMENTS).toInt()
            for (index in 0 until PREWARM_SEGMENTS) {
                drawArc(
                    color = if (index < filled) color else trackColor,
                    startAngle = -90f + index * slice + PREWARM_SEGMENT_GAP / 2f,
                    sweepAngle = slice - PREWARM_SEGMENT_GAP,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            imageVector = MaterialSymbols.Rounded.RocketLaunch,
            contentDescription = stringResource(MR.strings.prewarm_chapter_indicator),
            modifier = Modifier.size(PrewarmIconSize),
            tint = color,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.prewarm_start_now)) },
                onClick = {
                    onClick(ChapterDownloadAction.PREWARM_NOW)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterDownloadAction.PREWARM_CANCEL)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun ErrorIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START) },
                onClick = { onClick(ChapterDownloadAction.START) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Error,
            contentDescription = stringResource(MR.strings.chapter_error),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun Modifier.commonClickable(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.combinedClickable(
    enabled = enabled,
    onLongClick = {
        onLongClick()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    },
    onClick = onClick,
    role = Role.Button,
    interactionSource = null,
    indication = ripple(
        bounded = false,
        radius = IconButtonTokens.StateLayerSize / 2,
    ),
)

private val IndicatorSize = 26.dp
private val IndicatorPadding = 1.dp
private val IndicatorWavelength = 7.dp
private const val INDICATOR_AMPLITUDE = 0.6f
private const val TRACK_ALPHA = 0.25f

private val IndicatorStrokeWidth = 2.dp
private val PrewarmStrokeWidth = 2.dp
private val PrewarmIconSize = 11.dp
private const val PREWARM_SEGMENTS = 12
private const val PREWARM_SEGMENT_GAP = 12f
private val PrewarmIndicatorModifier = Modifier
    .size(IndicatorSize)
    .padding(IndicatorPadding)

private val IndicatorModifier = Modifier
    .size(IndicatorSize)
    .padding(IndicatorPadding)
private val ArrowModifier = Modifier
    .size(IndicatorSize)
