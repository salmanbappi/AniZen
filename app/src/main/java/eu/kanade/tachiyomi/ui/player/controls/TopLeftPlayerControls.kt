/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TopLeftPlayerControls(
    animeTitle: String,
    mediaTitle: String,
    onTitleClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    // ANZ -->
    episodeNumber: Float? = null,
    // ANZ <--
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ControlsButton(
            icon = Icons.AutoMirrored.Default.ArrowBack,
            onClick = onBackClick,
        )
        // ANZ -->
        PlayerTitle(
            animeTitle = animeTitle,
            mediaTitle = mediaTitle,
            episodeNumber = episodeNumber,
            onClick = onTitleClick,
            modifier = Modifier.weight(1f, fill = false),
        )
        // ANZ <--
    }
}

// ANZ -->
/**
 * Series name on top and a clean "Episodio X" below: the raw file / episode name that sources
 * return ("Ver Serie Episodio 1 Online") never shows up here.
 */
@Composable
fun PlayerTitle(
    animeTitle: String,
    mediaTitle: String,
    episodeNumber: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodeLabel = playerEpisodeLabel(animeTitle, mediaTitle, episodeNumber)
    val shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 8f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            // no ripple on the big title (it left an ugly box): the chip below is the visible button
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = animeTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                shadow = shadow,
            ),
        )
        if (episodeLabel.isNotBlank()) {
            // "Episodio 13 v": a pill that clearly opens the episode list
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onClick)
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = episodeLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun playerEpisodeLabel(animeTitle: String, mediaTitle: String, episodeNumber: Float?): String {
    if (episodeNumber != null && episodeNumber >= 0f) {
        val number = if (episodeNumber % 1f == 0f) {
            episodeNumber.toInt().toString()
        } else {
            episodeNumber.toString()
        }
        return stringResource(MR.strings.display_mode_episode, number)
    }
    return remember(animeTitle, mediaTitle) { cleanEpisodeName(animeTitle, mediaTitle) }
}

// Fallback when the source gave no episode number: strip the series name and the usual web noise.
private fun cleanEpisodeName(animeTitle: String, mediaTitle: String): String {
    var s = mediaTitle.trim()
    if (animeTitle.isNotBlank()) s = s.replace(animeTitle, "", ignoreCase = true)
    s = s.replace(Regex("^\\s*(ver|watch)\\s+", RegexOption.IGNORE_CASE), "")
        .replace(
            Regex("\\s*(online|sub\\s*espa[ñn]ol|latino|castellano|hd)\\s*$", RegexOption.IGNORE_CASE),
            "",
        )
        .trim(' ', '-', '–', ':', '|', '·')
    return s.ifBlank { mediaTitle.trim() }
}
// ANZ <--
