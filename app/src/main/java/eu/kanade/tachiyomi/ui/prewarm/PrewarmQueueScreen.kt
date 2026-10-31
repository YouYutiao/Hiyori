package eu.kanade.tachiyomi.ui.prewarm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.prewarm.PrewarmItem
import eu.kanade.tachiyomi.data.prewarm.PrewarmPauseReason
import eu.kanade.tachiyomi.data.prewarm.PrewarmSkipReason
import eu.kanade.tachiyomi.data.prewarm.PrewarmSort
import eu.kanade.tachiyomi.data.prewarm.PrewarmStatus
import eu.kanade.tachiyomi.data.prewarm.isActive
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
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
        val manualPaused by viewModel.isManualPaused.collectAsStateWithLifecycle()
        val batteryPolicy by viewModel.batteryPolicy.collectAsStateWithLifecycle()
        val prewarmAfterDownload by viewModel.prewarmAfterDownload.collectAsStateWithLifecycle()

        // Finished chapters leave the list on their own, the way finished downloads do. They stay in
        // the manager's state, because the manga list reads its "already warmed" badges from it.
        val visibleQueue = remember(queue) { queue.filter { it.status != PrewarmStatus.DONE } }
        val activeCount = queue.count { it.status.isActive }
        val waitingForCharge = queue.any {
            it.status == PrewarmStatus.PAUSED && it.pauseReason == PrewarmPauseReason.WAITING_CHARGE
        }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var showPolicyDialog by remember { mutableStateOf(false) }
        var fabExpanded by remember { mutableStateOf(true) }
        val fabNestedScroll = remember {
            // Collapse the pause/resume button to an icon while the list scrolls, same as the
            // download queue.
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }
            }
        }

        val rows = remember(visibleQueue) { buildRows(visibleQueue) }
        val listState = rememberLazyListState()
        // The move callback outlives a single recomposition, so it reads the latest rows instead of
        // the list captured when the drag state was created.
        val currentRows by rememberUpdatedState(rows)
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            moveRow(from.index, to.index, currentRows, viewModel)
        }
        val haptics = LocalHapticFeedback.current

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_prewarm_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (visibleQueue.isNotEmpty()) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "${visibleQueue.size}",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        // Sort and cancel only make sense with a queue; the settings entry does
                        // not, because turning prewarming on is the first thing a new user does.
                        if (queue.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            // Same shape as the download queue: grouped sort actions behind one icon.
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.prewarm_sort_queue_order)) },
                                    onClick = {
                                        sortExpanded = false
                                        viewModel.sortQueue(PrewarmSort.QUEUED)
                                    },
                                )
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                viewModel.sortQueue(PrewarmSort.CHAPTER_ASC)
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                viewModel.sortQueue(PrewarmSort.CHAPTER_DESC)
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                            }

                            AppBarActions(
                                listOfNotNull(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = MaterialSymbols.AutoMirroredRounded.Sort,
                                        onClick = { sortExpanded = true },
                                    ),
                                    if (waitingForCharge) {
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.prewarm_now),
                                            onClick = viewModel::prewarmNowOnce,
                                        )
                                    } else {
                                        null
                                    },
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.prewarm_settings),
                                        onClick = { showPolicyDialog = true },
                                    ),
                                    // Finished chapters stay listed, like finished downloads do, so
                                    // the run's result remains visible until it is dismissed here.
                                    if (activeCount > 0) {
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_cancel_all),
                                            onClick = viewModel::clearQueue,
                                        )
                                    } else if (visibleQueue.isNotEmpty()) {
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.prewarm_clear_finished),
                                            onClick = viewModel::clearQueue,
                                        )
                                    } else {
                                        null
                                    },
                                ),
                            )
                        } else {
                            AppBarActions(
                                listOf(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.prewarm_settings),
                                        onClick = { showPolicyDialog = true },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
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
                        expanded = fabExpanded,
                    )
                }
            },
        ) { contentPadding ->
            if (visibleQueue.isEmpty()) {
                EmptyScreen(
                    message = stringResource(MR.strings.prewarm_empty_guide),
                    modifier = Modifier.padding(contentPadding),
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
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(fabNestedScroll),
                contentPadding = contentPadding,
            ) {
                items(rows, key = { rowKey(it) }) { row ->
                    ReorderableItem(reorderState, key = rowKey(row)) { isDragging ->
                        val dragHandle = Modifier.draggableHandle(
                            onDragStarted = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                        when (row) {
                            is PrewarmRow.Header -> PrewarmHeaderRow(
                                title = row.title,
                                itemCount = row.count,
                                dragging = isDragging,
                                dragHandle = dragHandle,
                                onCancelSource = { viewModel.cancelSource(row.sourceId) },
                            )

                            is PrewarmRow.Chapter -> PrewarmItemRow(
                                item = row.item,
                                dragging = isDragging,
                                dragHandle = dragHandle,
                                onCancel = { viewModel.cancel(row.item) },
                                onRetry = { viewModel.retry(row.item.chapterId) },
                                onOpenManga = { navigator.push(MangaScreen(row.item.mangaId)) },
                                onMoveToTop = { viewModel.moveChapterToTop(row.item.chapterId) },
                                onMoveToBottom = { viewModel.moveChapterToBottom(row.item.chapterId) },
                            )
                        }
                    }
                }
            }
        }

        if (showPolicyDialog) {
            PrewarmSettingsDialog(
                selected = batteryPolicy,
                onSelect = { viewModel.setBatteryPolicy(it) },
                downloadPromptMode = prewarmAfterDownload,
                onResetDownloadPrompt = viewModel::resetDownloadPrompt,
                onDismiss = { showPolicyDialog = false },
            )
        }
    }

    private sealed interface PrewarmRow {
        data class Header(val sourceId: Long, val title: String, val count: Int) : PrewarmRow
        data class Chapter(val item: PrewarmItem) : PrewarmRow
    }

    private const val HEADER_KEY_PREFIX = "header-"

    private fun headerKey(sourceId: Long): String = "$HEADER_KEY_PREFIX$sourceId"

    private fun rowKey(row: PrewarmRow): Any = when (row) {
        is PrewarmRow.Header -> headerKey(row.sourceId)
        is PrewarmRow.Chapter -> row.item.chapterId
    }

    /**
     * Flattens the queue into the rendered rows: one source header, then its chapters.
     *
     * The screen no longer interleaves progress or statistics rows: they made the list read
     * differently from the download queue and pushed the chapters the user came for off screen.
     */
    private fun buildRows(queue: List<PrewarmItem>): List<PrewarmRow> = buildList {
        queue.groupBy { it.sourceId }.forEach { (sourceId, items) ->
            val title = items.first().sourceName.ifBlank { items.first().mangaTitle }
            add(PrewarmRow.Header(sourceId, title, items.size))
            items.forEach { add(PrewarmRow.Chapter(it)) }
        }
    }

    /**
     * Commits one drag step to the queue.
     *
     * A chapter only moves inside its own series block and a header only among headers, which is
     * the same containment the download queue enforces in [shouldMove]: letting a row walk into
     * another block would claim a chapter belongs to a different manga.
     */
    private fun moveRow(
        fromIndex: Int,
        toIndex: Int,
        rows: List<PrewarmRow>,
        viewModel: PrewarmQueueViewModel,
    ) {
        val from = rows.getOrNull(fromIndex) ?: return
        val to = rows.getOrNull(toIndex) ?: return
        when {
            from is PrewarmRow.Chapter && to is PrewarmRow.Chapter &&
                from.item.mangaId == to.item.mangaId -> {
                val block = rows.filterIsInstance<PrewarmRow.Chapter>()
                    .filter { it.item.mangaId == from.item.mangaId }
                val targetIndex = block.indexOfFirst { it.item.chapterId == to.item.chapterId }
                if (targetIndex >= 0) viewModel.moveChapter(from.item.chapterId, targetIndex)
            }

            from is PrewarmRow.Header && to is PrewarmRow.Header -> {
                val headers = rows.filterIsInstance<PrewarmRow.Header>()
                val targetIndex = headers.indexOfFirst { it.sourceId == to.sourceId }
                if (targetIndex >= 0) viewModel.moveSource(from.sourceId, targetIndex)
            }
        }
    }

    @Composable
    private fun batteryPolicyLabel(policy: Int): String = when (policy) {
        ReaderPreferences.BATTERY_POLICY_SUFFICIENT -> stringResource(MR.strings.prewarm_battery_policy_sufficient)
        ReaderPreferences.BATTERY_POLICY_ANY_TIME -> stringResource(MR.strings.prewarm_battery_policy_any_time)
        else -> stringResource(MR.strings.prewarm_battery_policy_charge_only)
    }

    /**
     * The queue's own settings: whether prewarming runs at all, and the charging rule it obeys.
     * Both belong to the queue rather than the reader's image settings.
     */
    @Composable
    private fun PrewarmSettingsDialog(
        selected: Int,
        onSelect: (Int) -> Unit,
        downloadPromptMode: Int,
        onResetDownloadPrompt: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(MR.strings.prewarm_settings)) },
            text = {
                Column {
                    Text(
                        text = stringResource(MR.strings.prewarm_uses_reader_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = stringResource(MR.strings.prewarm_battery_policy_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                            RadioButton(
                                selected = selected == value,
                                onClick = { onSelect(value) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(MR.strings.prewarm_battery_policy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    // The download-time question is asked from the manga screen; when it has been
                    // turned off, this is the only place that can bring it back.
                    Text(
                        text = stringResource(
                            MR.strings.prewarm_after_download_state,
                            stringResource(
                                when (downloadPromptMode) {
                                    ReaderPreferences.PREWARM_AFTER_DOWNLOAD_ALWAYS ->
                                        MR.strings.prewarm_after_download_state_always
                                    ReaderPreferences.PREWARM_AFTER_DOWNLOAD_NEVER ->
                                        MR.strings.prewarm_after_download_state_never
                                    else -> MR.strings.prewarm_after_download_state_ask
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (downloadPromptMode != ReaderPreferences.PREWARM_AFTER_DOWNLOAD_ASK) {
                        TextButton(
                            onClick = onResetDownloadPrompt,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(MR.strings.prewarm_after_download_reset))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_ok)) }
            },
        )
    }

    @Composable
    private fun PrewarmHeaderRow(
        title: String,
        itemCount: Int,
        dragging: Boolean,
        dragHandle: Modifier,
        onCancelSource: () -> Unit,
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Same shape as the download queue header: title plus how many rows it holds.
                text = "$title ($itemCount)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
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
                        text = { Text(stringResource(MR.strings.prewarm_cancel_source)) },
                        onClick = {
                            menuExpanded = false
                            onCancelSource()
                        },
                    )
                }
            }
            // Handle at the end, like the download queue's section header.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .then(dragHandle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.DragHandle,
                    contentDescription = stringResource(MR.strings.prewarm_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun PrewarmItemRow(
        item: PrewarmItem,
        dragging: Boolean,
        dragHandle: Modifier,
        onCancel: () -> Unit,
        onOpenManga: () -> Unit,
        onMoveToTop: () -> Unit,
        onMoveToBottom: () -> Unit,
        onRetry: () -> Unit,
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .clickable(onClick = onOpenManga)
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // An explicit handle keeps the row tappable for "open manga" while drag stays available.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(dragHandle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.DragHandle,
                    contentDescription = stringResource(MR.strings.prewarm_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                // Same two lines as a download row: manga first, then the chapter.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.mangaTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val progressText = progressText(item)
                    if (progressText.isNotEmpty()) {
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    text = chapterLine(item),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Always drawn, like the download row: a queued page simply sits at 0%.
                LinearProgressIndicator(
                    progress = { item.progressFraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.MoreVert,
                        contentDescription = stringResource(MR.strings.action_menu),
                    )
                }
                // Same actions as the download row's menu.
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    // Only a chapter that ended badly can be retried; the menu does not offer a
                    // no-op on rows that are queued or running.
                    if (item.status == PrewarmStatus.ERROR || item.status == PrewarmStatus.SKIPPED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.prewarm_retry)) },
                            onClick = {
                                menuExpanded = false
                                onRetry()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_move_to_top)) },
                        onClick = {
                            menuExpanded = false
                            onMoveToTop()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_move_to_bottom)) },
                        onClick = {
                            menuExpanded = false
                            onMoveToBottom()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (item.status.isActive) MR.strings.action_cancel else MR.strings.action_remove,
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onCancel()
                        },
                    )
                }
            }
        }
    }

    /** Download-style counter: how many pages of this chapter are already usable. */
    private fun progressText(item: PrewarmItem): String {
        if (item.totalPages <= 0) return ""
        return "${item.pagesCompleted}/${item.totalPages}"
    }

    /** Chapter name, with the pause/skip reason appended instead of a third text line. */
    @Composable
    private fun chapterLine(item: PrewarmItem): String {
        val reason = when (item.status) {
            PrewarmStatus.PAUSED -> when (item.pauseReason) {
                PrewarmPauseReason.WAITING_CHARGE -> stringResource(MR.strings.prewarm_paused_waiting_charge)
                PrewarmPauseReason.LOW_BATTERY -> stringResource(MR.strings.prewarm_paused_low_battery)
                PrewarmPauseReason.READER_ACTIVE -> stringResource(MR.strings.prewarm_paused_reader_active)
                PrewarmPauseReason.PREWARM_DISABLED -> stringResource(MR.strings.prewarm_paused_prewarm_disabled)
                PrewarmPauseReason.ENHANCEMENT_DISABLED -> {
                    stringResource(MR.strings.prewarm_paused_enhancement_disabled)
                }
                PrewarmPauseReason.MANUAL, PrewarmPauseReason.NONE -> null
            }
            PrewarmStatus.SKIPPED -> when (item.skipReason) {
                PrewarmSkipReason.CACHE_BUDGET_EXCEEDED -> stringResource(MR.strings.prewarm_skip_cache_budget)
                PrewarmSkipReason.AUTO_SPLIT_UNSUPPORTED -> stringResource(MR.strings.prewarm_skip_auto_split)
                PrewarmSkipReason.CHAPTER_UNAVAILABLE, null -> {
                    stringResource(MR.strings.prewarm_skip_chapter_unavailable)
                }
            }
            PrewarmStatus.ERROR -> stringResource(MR.strings.prewarm_status_error, item.pagesFailed)
            else -> null
        }
        return if (reason == null) item.chapterName else "${item.chapterName} · $reason"
    }

    private fun PrewarmItem.progressFraction(): Float {
        if (totalPages <= 0) return 0f
        return (pagesCompleted.toFloat() / totalPages).coerceIn(0f, 1f)
    }
}
