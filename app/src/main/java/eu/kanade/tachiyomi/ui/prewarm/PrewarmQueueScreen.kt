package eu.kanade.tachiyomi.ui.prewarm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.prewarm.PrewarmCompletionSummary
import eu.kanade.tachiyomi.data.prewarm.PrewarmItem
import eu.kanade.tachiyomi.data.prewarm.PrewarmPauseReason
import eu.kanade.tachiyomi.data.prewarm.PrewarmSessionStats
import eu.kanade.tachiyomi.data.prewarm.PrewarmSkipReason
import eu.kanade.tachiyomi.data.prewarm.PrewarmSort
import eu.kanade.tachiyomi.data.prewarm.PrewarmStatus
import eu.kanade.tachiyomi.data.prewarm.isActive
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction

object PrewarmQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<PrewarmQueueViewModel>()
        val queue by viewModel.queueState.collectAsStateWithLifecycle()
        val stats by viewModel.stats.collectAsStateWithLifecycle()
        val manualPaused by viewModel.isManualPaused.collectAsStateWithLifecycle()
        val batteryPolicy by viewModel.batteryPolicy.collectAsStateWithLifecycle()
        val lastCompletion by viewModel.lastCompletion.collectAsStateWithLifecycle()

        val activeCount = queue.count { it.status.isActive }
        // Session state lives in the app bar subtitle instead of a card at the top of the list:
        // the queue should read as a list of chapters, not as a dashboard.
        val cardState = prewarmCardState(queue, stats)
        val statusLine = prewarmStatusLine(cardState, queue, stats, lastCompletion)
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var showPolicyDialog by remember { mutableStateOf(false) }

        val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
        var sortExpanded by remember { mutableStateOf(false) }
        val rows = remember(queue, stats) { buildRows(queue, stats) }
        val listState = rememberLazyListState()
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()

        // One drag session at a time: either a chapter (inside its series) or a series header.
        var draggedChapterId by remember { mutableStateOf<Long?>(null) }
        var draggedSeriesId by remember { mutableStateOf<Long?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragAnchorTop by remember { mutableIntStateOf(0) }
        var dragAnchorHeight by remember { mutableIntStateOf(0) }

        fun beginDrag(key: Any, chapterId: Long?, seriesId: Long?) {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
            dragAnchorTop = info.offset
            dragAnchorHeight = info.size
            dragOffset = 0f
            draggedChapterId = chapterId
            draggedSeriesId = seriesId
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        fun updateDrag(delta: Float) {
            dragOffset += delta
            val center = dragAnchorTop + dragOffset + dragAnchorHeight / 2f
            val layout = listState.layoutInfo
            // Keep long queues usable: approaching an edge scrolls the list under the finger.
            when {
                center < layout.viewportStartOffset + 96f -> scope.launch { listState.scrollBy(-24f) }
                center > layout.viewportEndOffset - 96f -> scope.launch { listState.scrollBy(24f) }
            }
        }

        fun targetKeyUnderFinger(): Any? {
            val center = dragAnchorTop + dragOffset + dragAnchorHeight / 2f
            return listState.layoutInfo.visibleItemsInfo
                .firstOrNull { center >= it.offset && center <= it.offset + it.size }
                ?.key
        }

        fun endDrag() {
            val chapterId = draggedChapterId
            val seriesId = draggedSeriesId
            val target = targetKeyUnderFinger()
            when {
                chapterId != null && target is Long -> {
                    val item = queue.firstOrNull { it.chapterId == chapterId }
                    val block = item?.let { current -> queue.filter { it.mangaId == current.mangaId } }
                    val targetIndex = block?.indexOfFirst { it.chapterId == target } ?: -1
                    if (targetIndex >= 0) viewModel.moveChapter(chapterId, targetIndex)
                }
                seriesId != null && target is String && target.startsWith(HEADER_KEY_PREFIX) -> {
                    val targetSeries = target.removePrefix(HEADER_KEY_PREFIX).toLongOrNull()
                    val order = rows.mapNotNull { (it as? PrewarmRow.Header)?.mangaId }
                    val targetIndex = targetSeries?.let { order.indexOf(it) } ?: -1
                    if (targetIndex >= 0) viewModel.moveSeries(seriesId, targetIndex)
                }
            }
            draggedChapterId = null
            draggedSeriesId = null
            dragOffset = 0f
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_prewarm_queue),
                    subtitle = statusLine,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (queue.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { sortExpanded = true }) {
                                    Icon(
                                        imageVector = MaterialSymbols.AutoMirroredRounded.Sort,
                                        contentDescription = stringResource(MR.strings.action_sort),
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortExpanded,
                                    onDismissRequest = { sortExpanded = false },
                                ) {
                                    PrewarmSort.entries
                                        .filter { it != PrewarmSort.CUSTOM }
                                        .forEach { sort ->
                                            DropdownMenuItem(
                                                text = { Text(prewarmSortLabel(sort)) },
                                                leadingIcon = {
                                                    if (sortMode == sort) {
                                                        Icon(
                                                            imageVector = MaterialSymbols.Rounded.Check,
                                                            contentDescription = null,
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    sortExpanded = false
                                                    viewModel.sortQueue(sort)
                                                },
                                            )
                                        }
                                }
                            }
                        }
                        AppBarActions(
                            actions = buildList {
                                if (cardState == PrewarmCardState.WAITING_CHARGE) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.prewarm_now),
                                            onClick = viewModel::prewarmNowOnce,
                                        ),
                                    )
                                }
                                add(
                                    AppBar.OverflowAction(
                                        title = batteryPolicyLabel(batteryPolicy),
                                        onClick = { showPolicyDialog = true },
                                    ),
                                )
                                if (activeCount > 0) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_cancel_all),
                                            onClick = viewModel::clearQueue,
                                        ),
                                    )
                                }
                            },
                        )
                    },
                )
            },
            floatingActionButton = {
                if (activeCount > 0) {
                    SmallExtendedFloatingActionButton(
                        text = {
                            Text(
                                text = stringResource(
                                    if (manualPaused) MR.strings.action_resume else MR.strings.action_pause,
                                ),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (manualPaused) {
                                    MaterialSymbols.RoundedFilled.PlayArrow
                                } else {
                                    MaterialSymbols.RoundedFilled.Pause
                                },
                                contentDescription = null,
                            )
                        },
                        onClick = { if (manualPaused) viewModel.resume() else viewModel.pause() },
                    )
                }
            },
        ) { contentPadding ->
            if (queue.isEmpty()) {
                EmptyScreen(
                    message = stringResource(MR.strings.prewarm_empty_guide),
                    modifier = Modifier.padding(contentPadding),
                    subtitle = lastCompletion?.let {
                        stringResource(MR.strings.prewarm_more_last_run, it.chapterCount)
                    },
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.prewarm_empty_go_library,
                            icon = MaterialSymbols.Rounded.LocalLibrary,
                            onClick = navigator::popUntilRoot,
                        ),
                    ),
                )
                return@Scaffold
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                itemsIndexed(rows, key = { _, row -> rowKey(row) }) { _, row ->
                    when (row) {
                        is PrewarmRow.Progress -> PrewarmTotalProgress(row.completed, row.total)

                        is PrewarmRow.Header -> {
                            val dragging = draggedSeriesId == row.mangaId
                            PrewarmHeaderRow(
                                title = row.title,
                                itemCount = row.count,
                                dragging = dragging,
                                dragOffset = if (dragging) dragOffset else 0f,
                                dragKey = headerKey(row.mangaId),
                                onDragStart = { beginDrag(headerKey(row.mangaId), null, row.mangaId) },
                                onDrag = { delta -> updateDrag(delta) },
                                onDragEnd = { endDrag() },
                                onCancelSeries = { viewModel.cancelSeries(row.mangaId) },
                            )
                        }

                        is PrewarmRow.Chapter -> {
                            val dragging = draggedChapterId == row.item.chapterId
                            PrewarmItemRow(
                                item = row.item,
                                dragging = dragging,
                                dragOffset = if (dragging) dragOffset else 0f,
                                dragKey = row.item.chapterId,
                                onDragStart = { beginDrag(row.item.chapterId, row.item.chapterId, null) },
                                onDrag = { delta -> updateDrag(delta) },
                                onDragEnd = { endDrag() },
                                onCancel = { viewModel.cancel(row.item) },
                                onOpenManga = { navigator.push(MangaScreen(row.item.mangaId)) },
                            )
                        }

                        PrewarmRow.Stats -> Text(
                            text = stringResource(
                                MR.strings.prewarm_stats_summary,
                                stats.enhancedPages,
                                stats.cacheHitPages,
                                stats.failedPages,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }

        if (showPolicyDialog) {
            BatteryPolicyDialog(
                selected = batteryPolicy,
                onSelect = {
                    viewModel.setBatteryPolicy(it)
                    showPolicyDialog = false
                },
                onDismiss = { showPolicyDialog = false },
            )
        }
    }

    private enum class PrewarmCardState { RUNNING, WAITING_CHARGE, LOW_BATTERY, WAITING_READER, DONE }

    /**
     * Flattened rendering model: the drag gesture needs a stable key per row to translate a finger
     * position back into "which chapter / which series block", which raw queue indices cannot do
     * once headers and progress rows are interleaved.
     */
    private sealed interface PrewarmRow {
        data class Progress(val completed: Int, val total: Int) : PrewarmRow
        data class Header(val mangaId: Long, val title: String, val count: Int) : PrewarmRow
        data class Chapter(val item: PrewarmItem) : PrewarmRow
        object Stats : PrewarmRow
    }

    private const val HEADER_KEY_PREFIX = "header-"

    private fun headerKey(mangaId: Long): String = "$HEADER_KEY_PREFIX$mangaId"

    private fun rowKey(row: PrewarmRow): Any = when (row) {
        is PrewarmRow.Header -> headerKey(row.mangaId)
        is PrewarmRow.Chapter -> row.item.chapterId
        is PrewarmRow.Progress -> "total-progress"
        PrewarmRow.Stats -> "stats"
    }

    private fun buildRows(queue: List<PrewarmItem>, stats: PrewarmSessionStats): List<PrewarmRow> = buildList {
        val totalPages = queue.sumOf { it.totalPages }
        if (totalPages > 0) {
            add(PrewarmRow.Progress(queue.sumOf { it.pagesCompleted }, totalPages))
        }
        queue.groupBy { it.mangaId }.forEach { (mangaId, items) ->
            add(PrewarmRow.Header(mangaId, items.first().mangaTitle, items.size))
            items.forEach { add(PrewarmRow.Chapter(it)) }
        }
        if (stats.enhancedPages > 0 || stats.cacheHitPages > 0 || stats.failedPages > 0) {
            add(PrewarmRow.Stats)
        }
    }

    @Composable
    private fun prewarmSortLabel(sort: PrewarmSort): String = when (sort) {
        PrewarmSort.QUEUED -> stringResource(MR.strings.prewarm_sort_queue_order)
        PrewarmSort.CHAPTER_ASC -> stringResource(MR.strings.action_order_by_chapter_number) +
            " · " + stringResource(MR.strings.action_asc)
        PrewarmSort.CHAPTER_DESC -> stringResource(MR.strings.action_order_by_chapter_number) +
            " · " + stringResource(MR.strings.action_desc)
        PrewarmSort.CUSTOM -> ""
    }

    /**
     * One line describing what the queue is doing right now, shown as the app bar subtitle.
     * Pause reasons carry their own explanation so the charging rule reads as a default
     * behaviour instead of a hard limit.
     */
    @Composable
    private fun prewarmStatusLine(
        state: PrewarmCardState?,
        queue: List<PrewarmItem>,
        stats: PrewarmSessionStats,
        lastCompletion: PrewarmCompletionSummary?,
    ): String? {
        val runningItem = queue.firstOrNull { it.status == PrewarmStatus.RUNNING }
        return when (state) {
            null -> null
            // The running chapter is already highlighted in the list below, so the subtitle only
            // needs the state word and the page progress.
            PrewarmCardState.RUNNING -> if (runningItem != null && runningItem.totalPages > 0) {
                stringResource(MR.strings.prewarm_card_running) + " · " +
                    stringResource(
                        MR.strings.prewarm_status_running,
                        runningItem.pagesCompleted,
                        runningItem.totalPages,
                    )
            } else {
                stringResource(MR.strings.prewarm_card_running)
            }
            PrewarmCardState.WAITING_CHARGE -> stringResource(MR.strings.prewarm_card_waiting_charge) +
                " · " + stringResource(MR.strings.prewarm_card_waiting_charge_desc)
            PrewarmCardState.LOW_BATTERY -> stringResource(MR.strings.prewarm_card_low_battery) +
                " · " + stringResource(MR.strings.prewarm_card_low_battery_desc)
            PrewarmCardState.WAITING_READER -> stringResource(MR.strings.prewarm_card_waiting_reader) +
                " · " + stringResource(MR.strings.prewarm_card_waiting_reader_desc)
            // Prefers the just-finished run so a second batch does not report the sum of both.
            PrewarmCardState.DONE -> stringResource(MR.strings.prewarm_card_done) + " · " + stringResource(
                MR.strings.prewarm_card_done_desc,
                lastCompletion?.pageCount ?: stats.enhancedPages,
            )
        }
    }

    /**
     * One-line summary of what the queue is doing right now. Each pause reason is
     * paired with the action that lifts it, so the charging rule reads as a default
     * behaviour instead of a hard limit.
     */
    private fun prewarmCardState(
        queue: List<PrewarmItem>,
        stats: PrewarmSessionStats,
    ): PrewarmCardState? {
        val active = queue.filter { it.status.isActive }
        if (active.isEmpty()) {
            return if (queue.isNotEmpty() && stats.enhancedPages > 0) PrewarmCardState.DONE else null
        }
        if (active.any { it.status == PrewarmStatus.RUNNING }) return PrewarmCardState.RUNNING
        val pauseReasons = active
            .filter { it.status == PrewarmStatus.PAUSED }
            .mapTo(HashSet()) { it.pauseReason }
        if (PrewarmPauseReason.LOW_BATTERY in pauseReasons) return PrewarmCardState.LOW_BATTERY
        if (PrewarmPauseReason.WAITING_CHARGE in pauseReasons) return PrewarmCardState.WAITING_CHARGE
        if (PrewarmPauseReason.READER_ACTIVE in pauseReasons) return PrewarmCardState.WAITING_READER
        return null
    }

    @Composable
    private fun batteryPolicyLabel(policy: Int): String = when (policy) {
        ReaderPreferences.BATTERY_POLICY_SUFFICIENT -> stringResource(MR.strings.prewarm_battery_policy_sufficient)
        ReaderPreferences.BATTERY_POLICY_ANY_TIME -> stringResource(MR.strings.prewarm_battery_policy_any_time)
        else -> stringResource(MR.strings.prewarm_battery_policy_charge_only)
    }

    @Composable
    private fun BatteryPolicyDialog(
        selected: Int,
        onSelect: (Int) -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(MR.strings.prewarm_battery_policy_title)) },
            text = {
                Column {
                    listOf(
                        ReaderPreferences.BATTERY_POLICY_CHARGE_ONLY to MR.strings.prewarm_battery_policy_charge_only,
                        ReaderPreferences.BATTERY_POLICY_SUFFICIENT to MR.strings.prewarm_battery_policy_sufficient,
                        ReaderPreferences.BATTERY_POLICY_ANY_TIME to MR.strings.prewarm_battery_policy_any_time,
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(value) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            RadioButton(selected = selected == value, onClick = { onSelect(value) })
                        }
                    }
                    Text(
                        text = stringResource(MR.strings.prewarm_battery_policy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_ok)) }
            },
        )
    }

    /**
     * Session-wide progress: every queued chapter counts, so the bar also covers
     * pages that were already cached from an earlier run.
     */
    @Composable
    private fun PrewarmTotalProgress(completed: Int, total: Int) {
        val fraction = (completed.toFloat() / total).coerceIn(0f, 1f)
        val label = stringResource(MR.strings.prewarm_total_progress, completed, total)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .semantics { contentDescription = label },
            )
        }
    }

    @Composable
    private fun PrewarmHeaderRow(
        title: String,
        itemCount: Int,
        dragging: Boolean,
        dragOffset: Float,
        dragKey: Any,
        onDragStart: () -> Unit,
        onDrag: (Float) -> Unit,
        onDragEnd: () -> Unit,
        onCancelSeries: () -> Unit,
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = dragOffset }
                .background(
                    if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                )
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Dragging the header moves the whole series block; rows can only move inside it.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pointerInput(dragKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, delta ->
                                change.consume()
                                onDrag(delta.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.DragHandle,
                    contentDescription = stringResource(MR.strings.prewarm_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // Same shape as the download queue header: title plus how many rows it holds.
                text = "$title ($itemCount)",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.MoreVert,
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.prewarm_cancel_series)) },
                        onClick = {
                            menuExpanded = false
                            onCancelSeries()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun PrewarmItemRow(
        item: PrewarmItem,
        dragging: Boolean,
        dragOffset: Float,
        dragKey: Any,
        onDragStart: () -> Unit,
        onDrag: (Float) -> Unit,
        onDragEnd: () -> Unit,
        onCancel: () -> Unit,
        onOpenManga: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = dragOffset }
                .background(
                    if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                )
                .clickable(onClick = onOpenManga)
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // An explicit handle keeps the row tappable for "open manga" while drag stays available.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pointerInput(dragKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, delta ->
                                change.consume()
                                onDrag(delta.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.DragHandle,
                    contentDescription = stringResource(MR.strings.prewarm_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.chapterName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text(
                    text = statusText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                // A bar only while the pages are actually moving: finished rows already say how many
                // pages they produced, and queued rows would just draw a 0% bar.
                if (item.totalPages > 0 &&
                    (
                        item.status == PrewarmStatus.RUNNING ||
                            (item.status == PrewarmStatus.PAUSED && item.pagesCompleted > 0)
                        )
                ) {
                    val progressLabel = stringResource(
                        MR.strings.prewarm_chapter_progress,
                        item.pagesCompleted,
                        item.totalPages,
                    )
                    LinearProgressIndicator(
                        progress = { (item.pagesCompleted.toFloat() / item.totalPages).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .semantics { contentDescription = progressLabel },
                    )
                }
            }
            // Finished, skipped and failed rows can be dismissed too: the queue is a list of
            // results as much as a list of pending work.
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Close,
                    contentDescription = stringResource(
                        if (item.status.isActive) MR.strings.action_cancel else MR.strings.action_remove,
                    ),
                )
            }
        }
    }

    @Composable
    private fun statusText(item: PrewarmItem): String {
        return when (item.status) {
            PrewarmStatus.QUEUED -> stringResource(MR.strings.prewarm_status_queued)
            PrewarmStatus.RUNNING -> if (item.totalPages > 0) {
                stringResource(MR.strings.prewarm_status_running, item.pagesCompleted, item.totalPages)
            } else {
                stringResource(MR.strings.prewarm_status_queued)
            }
            PrewarmStatus.PAUSED -> {
                val reason = when (item.pauseReason) {
                    PrewarmPauseReason.WAITING_CHARGE -> stringResource(MR.strings.prewarm_paused_waiting_charge)
                    PrewarmPauseReason.LOW_BATTERY -> stringResource(MR.strings.prewarm_paused_low_battery)
                    PrewarmPauseReason.READER_ACTIVE -> stringResource(MR.strings.prewarm_paused_reader_active)
                    PrewarmPauseReason.PREWARM_DISABLED -> stringResource(MR.strings.prewarm_paused_prewarm_disabled)
                    PrewarmPauseReason.ENHANCEMENT_DISABLED -> {
                        stringResource(MR.strings.prewarm_paused_enhancement_disabled)
                    }
                    PrewarmPauseReason.MANUAL, PrewarmPauseReason.NONE -> null
                }
                val base = stringResource(MR.strings.paused)
                if (reason != null) "$base • $reason" else base
            }
            PrewarmStatus.DONE -> stringResource(MR.strings.prewarm_status_done, item.totalPages)
            PrewarmStatus.SKIPPED -> when (item.skipReason) {
                PrewarmSkipReason.CACHE_BUDGET_EXCEEDED -> stringResource(MR.strings.prewarm_skip_cache_budget)
                PrewarmSkipReason.CHAPTER_UNAVAILABLE, null -> {
                    stringResource(MR.strings.prewarm_skip_chapter_unavailable)
                }
                PrewarmSkipReason.AUTO_SPLIT_UNSUPPORTED -> stringResource(MR.strings.prewarm_skip_auto_split)
            }
            PrewarmStatus.ERROR -> stringResource(MR.strings.prewarm_status_error, item.pagesFailed)
        }
    }
}
