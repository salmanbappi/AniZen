package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.collections.immutable.ImmutableSet
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.KEIYOUSHI_SIGNATURE
import mihon.domain.extensionrepo.model.SALMANBAPPI_SIGNATURE
import mihon.domain.extensionrepo.model.YUZONO_SIGNATURE
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord

@Composable
fun ExtensionReposContent(
    repos: ImmutableSet<ExtensionRepo>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onOpenWebsite: (ExtensionRepo) -> Unit,
    onOpenDiscord: (ExtensionRepo) -> Unit,
    onClickDelete: (String) -> Unit,
    onToggleVisibility: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = lazyListState,
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        modifier = modifier,
    ) {
        repos.forEach {
            item {
                ExtensionRepoListItem(
                    modifier = Modifier.animateItem(),
                    repo = it,
                    onOpenWebsite = { onOpenWebsite(it) },
                    onOpenDiscord = { onOpenDiscord(it) },
                    onDelete = { onClickDelete(it.baseUrl) },
                    onToggleVisibility = { onToggleVisibility(it.baseUrl, !it.isVisible) },
                )
            }
        }
    }
}

@Composable
private fun ExtensionRepoListItem(
    repo: ExtensionRepo,
    onOpenWebsite: () -> Unit,
    onOpenDiscord: () -> Unit,
    onDelete: () -> Unit,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val iconAlpha = if (repo.isVisible) 1f else 0.4f

    ElevatedCard(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium),
        ) {
            val fallbackPainter = painterResource(repoResId(repo.signingKeyFingerprint))
            if (repo.icon != null) {
                AsyncImage(
                    model = repo.icon,
                    contentDescription = null,
                    placeholder = fallbackPainter,
                    error = fallbackPainter,
                    alpha = iconAlpha,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .align(Alignment.CenterVertically),
                )
            } else {
                Image(
                    painter = fallbackPainter,
                    contentDescription = null,
                    alpha = iconAlpha,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .align(Alignment.CenterVertically),
                )
            }

            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.padding.medium,
                            top = MaterialTheme.padding.medium,
                            end = MaterialTheme.padding.medium,
                        ),
                ) {
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalContentColor.current.let {
                            if (repo.isVisible) it else it.copy(alpha = 0.6f)
                        },
                        textDecoration = TextDecoration.LineThrough.takeIf { !repo.isVisible },
                    )
                    repo.author?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onOpenWebsite) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = stringResource(MR.strings.action_open_in_browser),
                        )
                    }

                    if (repo.discord != null) {
                        IconButton(onClick = onOpenDiscord) {
                            Icon(
                                imageVector = CustomIcons.Discord,
                                contentDescription = stringResource(MR.strings.action_open_discord),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val url = "${repo.baseUrl}/index.min.json"
                            context.copyToClipboard(url, url)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        )
                    }

                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (repo.isVisible) {
                                Icons.Outlined.Visibility
                            } else {
                                Icons.Outlined.VisibilityOff
                            },
                            contentDescription = stringResource(
                                if (repo.isVisible) MR.strings.action_hide else MR.strings.action_show,
                            ),
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Picks a bundled repo icon from the repo's signing key fingerprint.
 * Repos can also provide their own icon via the `icon` field of their repo.json,
 * which takes precedence over this mapping.
 */
fun repoResId(fingerprint: String) = when (fingerprint) {
    YUZONO_SIGNATURE -> R.mipmap.repo_yuzono
    KEIYOUSHI_SIGNATURE -> R.mipmap.repo_keiyoushi
    SALMANBAPPI_SIGNATURE -> R.mipmap.repo_salmanbappi
    else -> R.mipmap.repo_default
}
