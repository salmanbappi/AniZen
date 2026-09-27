package mihon.feature.announcements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun AnnouncementCard(entry: AnnouncementEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            AsyncImage(
                model = entry.coverImageUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 90.dp, height = 130.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                CategoryBadge(category = entry.category)
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = expectedOrCountdownText(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: AnnouncementCategory) {
    val (backgroundColor, label) = when (category) {
        AnnouncementCategory.NEW_SEASON -> Color(0xFFC98A1F) to "New Season"
        AnnouncementCategory.ADAPTATION -> Color(0xFF2D6CDF) to "Adaptation"
        AnnouncementCategory.SPIN_OFF -> Color(0xFF7C4DFF) to "Spin-off"
        AnnouncementCategory.REMAKE -> Color(0xFF00897B) to "Remake"
        AnnouncementCategory.ORIGINAL -> Color(0xFF546E7A) to "Original"
        AnnouncementCategory.MOVIE -> Color(0xFFD23B6E) to "Movie"
        AnnouncementCategory.SPECIAL -> Color(0xFF8E44AD) to "Special / OVA / ONA"
    }
    Surface(color = backgroundColor, shape = RoundedCornerShape(50)) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

fun expectedOrCountdownText(entry: AnnouncementEntry): String {
    val exact = entry.exactReleaseDate
    if (exact == null) {
        return entry.expectedYear?.let { "Expected $it" } ?: ""
    }
    val remaining = exact - System.currentTimeMillis()
    if (remaining <= 0) return "Releasing now"
    val days = remaining / (24 * 60 * 60 * 1000)
    val hours = (remaining / (60 * 60 * 1000)) % 24
    val minutes = (remaining / (60 * 1000)) % 60
    return "${days}d ${hours}h ${minutes}m"
}