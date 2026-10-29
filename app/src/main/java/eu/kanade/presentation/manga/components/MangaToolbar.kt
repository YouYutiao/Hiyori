package eu.kanade.presentation.manga.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.PrewarmDropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.PrewarmAction
import eu.kanade.presentation.manga.PrewarmMenuCount
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.RocketLaunch
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun MangaToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickFilter: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickPrewarm: ((PrewarmAction) -> Unit)?,
    onClickCloudSync: (() -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickRefresh: () -> Unit,
    onClickMigrate: (() -> Unit)?,
    onClickEditNotes: () -> Unit,

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
    prewarmEnabled: Boolean,
    // No default: an empty map silently downgrades the prewarm menu to "everything enabled, no
    // counts", so every caller has to decide what it passes.
    prewarmMenuCounts: Map<PrewarmAction, PrewarmMenuCount>,
    onPrewarmDisabledClick: (() -> Unit)?,
) {
    val isActionMode = actionModeCounter > 0
    AppBar(
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(title, modifier = Modifier.alpha(titleAlphaProvider()))
            }
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isActionMode) 1f else backgroundAlphaProvider()),
        navigateUp = navigateUp,
        actions = {
            var downloadExpanded by remember { mutableStateOf(false) }
            if (onClickDownload != null) {
                val onDismissRequest = { downloadExpanded = false }
                DownloadDropdownMenu(
                    expanded = downloadExpanded,
                    onDismissRequest = onDismissRequest,
                    onDownloadClicked = onClickDownload,
                )
            }

            var prewarmExpanded by remember { mutableStateOf(false) }
            if (onClickPrewarm != null) {
                val onDismissRequest = { prewarmExpanded = false }
                PrewarmDropdownMenu(
                    expanded = prewarmExpanded,
                    onDismissRequest = onDismissRequest,
                    onPrewarmClicked = onClickPrewarm,
                    counts = prewarmMenuCounts,
                )
            }

            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                actions = buildList {
                    if (isActionMode) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = MaterialSymbols.Rounded.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = MaterialSymbols.Rounded.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        )
                        return@buildList
                    }
                    if (onClickDownload != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.manga_download),
                                icon = MaterialSymbols.Rounded.Download,
                                onClick = { downloadExpanded = !downloadExpanded },
                            ),
                        )
                    }
                    if (onClickPrewarm != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_prewarm),
                                icon = MaterialSymbols.Rounded.RocketLaunch,
                                iconTint = if (prewarmEnabled) {
                                    null
                                } else {
                                    LocalContentColor.current.copy(alpha = 0.38f)
                                },
                                onClick = {
                                    if (prewarmEnabled) {
                                        prewarmExpanded = !prewarmExpanded
                                    } else {
                                        onPrewarmDisabledClick?.invoke()
                                    }
                                },
                            ),
                        )
                    }
                    if (onClickCloudSync != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.cloud_sync),
                                icon = MaterialSymbols.Rounded.ArrowUpward,
                                onClick = onClickCloudSync,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = MaterialSymbols.Rounded.FilterList,
                            iconTint = filterTint,
                            onClick = onClickFilter,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_webview_refresh),
                            onClick = onClickRefresh,
                        ),
                    )
                    if (onClickEditCategory != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit_categories),
                                onClick = onClickEditCategory,
                            ),
                        )
                    }
                    if (onClickMigrate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_migrate),
                                onClick = onClickMigrate,
                            ),
                        )
                    }
                    if (onClickShare != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = onClickShare,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_notes),
                            onClick = onClickEditNotes,
                        ),
                    )
                },
            )
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )
}
