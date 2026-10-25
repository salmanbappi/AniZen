package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel.Dialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.BulkFavoriteDialogs(
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
    dialog: Dialog?,
) {
    val navigator = LocalNavigator.current

    when (dialog) {
        is Dialog.ChangeAnimesCategory ->
            ChangeAnimesCategoryDialog(
                dialog = dialog,
                navigator = navigator,
                onDismiss = bulkFavoriteScreenModel::dismissDialog,
                onConfirm = { include, exclude ->
                    bulkFavoriteScreenModel.setAnimesCategories(dialog.animes, include, exclude)
                },
            )
        else -> {}
    }
}

@Composable
private fun ChangeAnimesCategoryDialog(
    dialog: Dialog.ChangeAnimesCategory,
    navigator: Navigator?,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>, List<Long>) -> Unit,
) {
    ChangeCategoryDialog(
        initialSelection = dialog.initialSelection,
        onDismissRequest = onDismiss,
        onEditCategories = { navigator?.push(CategoryScreen) },
        onConfirm = onConfirm,
    )
}

@Composable
fun bulkSelectionButton(
    isRunning: Boolean,
    toggleSelectionMode: () -> Unit,
): AppBar.AppBarAction {
    val title = stringResource(KMR.strings.action_bulk_select)
    return if (isRunning) {
        AppBar.Action(
            title = title,
            onClick = {},
            iconContent = {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            },
        )
    } else {
        AppBar.Action(
            title = title,
            icon = Icons.Outlined.Checklist,
            onClick = toggleSelectionMode,
        )
    }
}
