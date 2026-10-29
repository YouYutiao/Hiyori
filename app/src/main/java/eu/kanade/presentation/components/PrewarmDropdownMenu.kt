package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.PrewarmAction
import eu.kanade.presentation.manga.PrewarmMenuCount
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun PrewarmDropdownMenu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onPrewarmClicked: (PrewarmAction) -> Unit,
    offset: DpOffset? = null,
    // No default: an empty map silently downgrades every item to "enabled, no count", which is
    // indistinguishable from "nothing can be prewarmed" for the user.
    counts: Map<PrewarmAction, PrewarmMenuCount>,
) {
    if (offset != null) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            content = {
                PrewarmDropdownMenuItems(
                    onDismissRequest = onDismissRequest,
                    onPrewarmClicked = onPrewarmClicked,
                    counts = counts,
                )
            },
        )
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = {
                PrewarmDropdownMenuItems(
                    onDismissRequest = onDismissRequest,
                    onPrewarmClicked = onPrewarmClicked,
                    counts = counts,
                )
            },
        )
    }
}

@Composable
private fun PrewarmDropdownMenuItems(
    onDismissRequest: () -> Unit,
    onPrewarmClicked: (PrewarmAction) -> Unit,
    counts: Map<PrewarmAction, PrewarmMenuCount>,
) {
    val options = listOf(
        PrewarmAction.NEXT_1_CHAPTER to pluralStringResource(MR.plurals.prewarm_amount, 1, 1),
        PrewarmAction.NEXT_5_CHAPTERS to pluralStringResource(MR.plurals.prewarm_amount, 5, 5),
        PrewarmAction.NEXT_10_CHAPTERS to pluralStringResource(MR.plurals.prewarm_amount, 10, 10),
        PrewarmAction.NEXT_25_CHAPTERS to pluralStringResource(MR.plurals.prewarm_amount, 25, 25),
        PrewarmAction.UNREAD_CHAPTERS to stringResource(MR.strings.download_unread),
        PrewarmAction.BOOKMARKED_CHAPTERS to stringResource(MR.strings.download_bookmarked),
    )

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        options.forEach { (prewarmAction, string) ->
            val count = counts[prewarmAction]
            DropdownMenuItem(
                text = {
                    Text(
                        text = when {
                            count == null || count.total == 0 -> string
                            // A disabled item never fires onClick, so the reason it is disabled has
                            // to live in the label instead of a snackbar.
                            count.downloaded == 0 -> stringResource(
                                MR.strings.prewarm_menu_none_downloaded,
                                string,
                            )
                            else -> stringResource(
                                MR.strings.prewarm_menu_downloaded_count,
                                string,
                                count.downloaded,
                            )
                        },
                    )
                },
                enabled = count?.enabled ?: true,
                onClick = {
                    onPrewarmClicked(prewarmAction)
                    onDismissRequest()
                },
            )
        }

        Text(
            text = stringResource(MR.strings.prewarm_menu_hint),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .secondaryItemAlpha(),
        )
    }
}
