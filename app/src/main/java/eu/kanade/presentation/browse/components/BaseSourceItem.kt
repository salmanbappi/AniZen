package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.tachiyomi.network.model.NodeStatus
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Magnet
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun BaseSourceItem(
    item: SourceUiModel.Item,
    modifier: Modifier = Modifier,
    onClickItem: () -> Unit = {},
    onLongClickItem: () -> Unit = {},
    icon: @Composable RowScope.(Source) -> Unit = defaultIcon,
    action: @Composable RowScope.(Source) -> Unit = {},
    content: @Composable RowScope.(SourceUiModel.Item) -> Unit = defaultContent,
) {
    BaseBrowseItem(
        modifier = modifier,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        icon = { icon.invoke(this, item.source) },
        action = { action.invoke(this, item.source) },
        content = { content.invoke(this, item) },
    )
}

private val defaultIcon: @Composable RowScope.(Source) -> Unit = { source ->
    SourceIcon(source = source)
}

private val defaultContent: @Composable RowScope.(SourceUiModel.Item) -> Unit = { item ->
    Column(
        modifier = Modifier
            .padding(horizontal = MaterialTheme.padding.medium)
            .weight(1f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = buildAnnotatedString {
                if (item.isTorrent) {
                    appendInlineContent(TORRENT_ICON, "(Torrent)")
                    append(" ")
                }
                append(item.displayName)
            }

            val inlineContent = mapOf(
                Pair(
                    TORRENT_ICON,
                    InlineTextContent(
                        Placeholder(
                            width = MaterialTheme.typography.bodyMedium.fontSize,
                            height = MaterialTheme.typography.bodyMedium.fontSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        ),
                    ) {
                        Icon(
                            imageVector = CustomIcons.Magnet,
                            contentDescription = "(Torrent)",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                ),
            )

            Text(
                text = text,
                inlineContent = inlineContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
            )

            
            if (item.isApi) {
                StatusBadge("API", Color(0xFF43A047))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.secondaryText.isNotEmpty()) {
                Text(
                    modifier = Modifier.secondaryItemAlpha(),
                    text = item.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (item.status != null) {
                // Health Pulse
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (item.status) {
                                NodeStatus.OPERATIONAL -> Color(0xFF4CAF50)
                                NodeStatus.DEGRADED -> Color(0xFFFFC107)
                                else -> Color(0xFFF44336)
                            }
                        )
                )
            }

            if (item.isNsfw) {
                Text(
                    text = "18+",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif
        )
    }
}

private const val TORRENT_ICON = "torrentIcon"

