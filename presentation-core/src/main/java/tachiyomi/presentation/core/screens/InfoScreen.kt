package tachiyomi.presentation.core.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.tvGlowFocusHighlight

@Composable
fun InfoScreen(
    icon: ImageVector,
    headingText: String,
    subtitleText: String,
    acceptText: String,
    onAcceptClick: () -> Unit,
    canAccept: Boolean = true,
    rejectText: String? = null,
    onRejectClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscapeOrWide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.screenWidthDp >= 600

    if (isLandscapeOrWide) {
        Scaffold { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(MaterialTheme.padding.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                // Left Pane: Info & Actions
                Column(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 340.dp)
                        .fillMaxHeight()
                        .padding(MaterialTheme.padding.small),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(bottom = MaterialTheme.padding.small)
                                .size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = headingText,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = subtitleText,
                            modifier = Modifier
                                .secondaryItemAlpha()
                                .padding(top = MaterialTheme.padding.small),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.padding.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvGlowFocusHighlight(),
                            enabled = canAccept,
                            onClick = onAcceptClick,
                        ) {
                            Text(text = acceptText)
                        }
                        if (rejectText != null && onRejectClick != null) {
                            OutlinedButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvGlowFocusHighlight(),
                                onClick = onRejectClick,
                            ) {
                                Text(text = rejectText)
                            }
                        }
                    }
                }

                // Right Pane: Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    content()
                }
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                val outlineColor = MaterialTheme.colorScheme.outline
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .drawBehind {
                            val strokeWidth = Dp.Hairline.value
                            val y = 0f
                            drawLine(
                                color = outlineColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = strokeWidth,
                            )
                        }
                        .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                        .padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        )
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvGlowFocusHighlight(),
                        enabled = canAccept,
                        onClick = onAcceptClick,
                    ) {
                        Text(text = acceptText)
                    }
                    if (rejectText != null && onRejectClick != null) {
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvGlowFocusHighlight(),
                            onClick = onRejectClick,
                        ) {
                            Text(text = rejectText)
                        }
                    }
                }
            },
        ) { paddingValues ->
            // Status bar scrim
            Box(
                modifier = Modifier
                    .zIndex(2f)
                    .secondaryItemAlpha()
                    .background(MaterialTheme.colorScheme.background)
                    .fillMaxWidth()
                    .height(paddingValues.calculateTopPadding()),
            )

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(paddingValues)
                    .padding(top = 48.dp)
                    .padding(horizontal = MaterialTheme.padding.medium),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(bottom = MaterialTheme.padding.small)
                        .size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = headingText,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = subtitleText,
                    modifier = Modifier
                        .secondaryItemAlpha()
                        .padding(vertical = MaterialTheme.padding.small),
                    style = MaterialTheme.typography.titleSmall,
                )

                content()
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun InfoScaffoldPreview() {
    InfoScreen(
        icon = Icons.Outlined.Newspaper,
        headingText = "Heading",
        subtitleText = "Subtitle",
        acceptText = "Accept",
        onAcceptClick = {},
        rejectText = "Reject",
        onRejectClick = {},
    ) {
        Text("Hello world")
    }
}
