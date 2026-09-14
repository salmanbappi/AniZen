package tachiyomi.presentation.core.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SkeletonItem(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
) {
    Box(
        modifier = modifier
            .then(
                if (shape != RectangleShape) {
                    Modifier.graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                drawRect(color = color)
            },
    )
}

@Composable
fun SkeletonAnimeCard(
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    ratio: Float = 2f / 3f,
) {
    Column(
        modifier = modifier.width(width),
    ) {
        SkeletonItem(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonItem(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        SkeletonItem(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp),
        )
    }
}

@Composable
fun SkeletonFeedIsland(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            SkeletonItem(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .width(150.dp)
                    .height(20.dp),
            )

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                repeat(4) {
                    SkeletonAnimeCard()
                }
            }
        }
    }
}
