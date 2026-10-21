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

package eu.kanade.tachiyomi.ui.player.controls.components.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import eu.kanade.presentation.player.components.ExpandableCard
import eu.kanade.presentation.player.components.SliderItem
import eu.kanade.tachiyomi.ui.player.DebandSettings
import eu.kanade.tachiyomi.ui.player.Debanding
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.VideoFilterTheme
import eu.kanade.tachiyomi.ui.player.VideoFilters
import eu.kanade.tachiyomi.ui.player.applyAnime4K
import eu.kanade.tachiyomi.ui.player.applyDebandMode
import eu.kanade.tachiyomi.ui.player.applyDebandSetting
import eu.kanade.tachiyomi.ui.player.applyFilter
import eu.kanade.tachiyomi.ui.player.applyInterpolation
import eu.kanade.tachiyomi.ui.player.applyTheme
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import eu.kanade.tachiyomi.ui.player.controls.CARDS_MAX_WIDTH
import eu.kanade.tachiyomi.ui.player.controls.panelCardsColors
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.InterpolationMode
import eu.kanade.tachiyomi.util.system.findActivity
import eu.kanade.tachiyomi.util.system.getDisplayRefreshRate
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

@Composable
fun VideoFiltersPanel(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConstraintLayout(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest,
            )
            .padding(MaterialTheme.padding.medium),
    ) {
        val settingsCard = createRef()

        Card(
            modifier = Modifier
                .constrainAs(settingsCard) {
                    top.linkTo(parent.top)
                    end.linkTo(parent.end)
                }
                .widthIn(max = CARDS_MAX_WIDTH)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            colors = panelCardsColors(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(MR.strings.player_sheets_filters_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(32.dp))
                    }
                }

                FilterPresetsCard()
                FiltersCard()
                DebandCard()
                Anime4KCard()
                // ANZ -->
                InterpolationCard()
                // ANZ <--
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPresetsCard() {
    val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
    var isExpanded by remember { mutableStateOf(false) }
    // ANZ -->
    val activity = LocalContext.current as? PlayerActivity
    val mpv = activity?.viewModel?.mpv
    // ANZ <--

    // Collect current values for matching
    val brightness by decoderPreferences.brightnessFilter().collectAsState()
    val contrast by decoderPreferences.contrastFilter().collectAsState()
    val saturation by decoderPreferences.saturationFilter().collectAsState()
    val gamma by decoderPreferences.gammaFilter().collectAsState()
    val hue by decoderPreferences.hueFilter().collectAsState()
    val sharpen by decoderPreferences.sharpenFilter().collectAsState()

    val currentPreset = VideoFilterTheme.entries.find { preset ->
        preset.brightness == brightness &&
        preset.contrast == contrast &&
        preset.saturation == saturation &&
        preset.gamma == gamma &&
        preset.hue == hue &&
        preset.sharpen == sharpen
    }

    ExpandableCard(
        isExpanded = isExpanded,
        onExpand = { isExpanded = it },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                Icon(Icons.Default.AutoAwesome, null)
                Text(stringResource(MR.strings.player_sheets_filters_themes))
            }
        },
        colors = panelCardsColors(),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                modifier = Modifier.fillMaxWidth(),
            ) {
                VideoFilterTheme.entries.forEach { theme ->
                    key("preset-${theme.name}") {
                        InputChip(
                            selected = currentPreset == theme,
                            onClick = {
                                decoderPreferences.videoFilterTheme().set(theme.ordinal)
                                // ANZ -->
                                applyTheme(mpv, theme, decoderPreferences)
                                // ANZ <--
                            },
                            label = { Text(stringResource(theme.titleRes)) },
                        )
                    }
                }
            }
            
            currentPreset?.let {
                if (it.description.isNotEmpty()) {
                    Text(
                        text = it.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FiltersCard() {
    val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
    var isExpanded by remember { mutableStateOf(true) }
    // ANZ -->
    val activity = LocalContext.current as? PlayerActivity
    val mpv = activity?.viewModel?.mpv
    // ANZ <--

    ExpandableCard(
        isExpanded = isExpanded,
        onExpand = { isExpanded = it },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                Icon(Icons.Default.Tune, null)
                Text(stringResource(MR.strings.player_sheets_filters_title))
            }
        },
        colors = panelCardsColors(),
    ) {
        Column {
            TextButton(
                onClick = {
                    VideoFilters.entries.forEach {
                        it.preference(decoderPreferences).delete()
                    }
                    // ANZ -->
                    mpv?.setPropertyString("vf", "")
                    mpv?.setPropertyInt("brightness", 0)
                    mpv?.setPropertyInt("contrast", 0)
                    mpv?.setPropertyInt("saturation", 0)
                    mpv?.setPropertyInt("gamma", 0)
                    mpv?.setPropertyInt("hue", 0)
                    mpv?.setPropertyInt("sharpen", 0)
                    // ANZ <--
                },
            ) {
                Text(text = stringResource(MR.strings.action_reset))
            }

            VideoFilters.entries.forEach { filter ->
                key("filter-${filter.name}") {
                    val value by filter.preference(decoderPreferences).collectAsState()
                    // Draw tick marks (dots) if the range is small enough (e.g. Sharpening: -20 to 20)
                    val range = filter.max - filter.min
                    val steps = if (range <= 40) range - 1 else 0

                    SliderItem(
                        label = stringResource(filter.titleRes),
                        value = value.toFloat(),
                        valueText = value.toString(),
                        onChange = {
                            filter.preference(decoderPreferences).set(it.toInt())
                            // ANZ -->
                            applyFilter(mpv, filter, it.toInt(), decoderPreferences)
                            // ANZ <--
                        },
                        max = filter.max.toFloat(),
                        min = filter.min.toFloat(),
                        steps = steps,
                    )
                }
            }
        }
    }
}

@Composable
fun DebandCard() {
    val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
    val debandMode by decoderPreferences.videoDebanding().collectAsState()
    var isExpanded by remember { mutableStateOf(true) }
    // ANZ -->
    val activity = LocalContext.current as? PlayerActivity
    val mpv = activity?.viewModel?.mpv
    // ANZ <--

    ExpandableCard(
        isExpanded = isExpanded,
        onExpand = { isExpanded = it },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                Icon(Icons.Default.Gradient, null)
                Text(stringResource(MR.strings.player_sheets_deband_title))
            }
        },
        colors = panelCardsColors(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = MaterialTheme.padding.extraSmall, end = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Debanding.entries.forEach { mode ->
                    IconToggleButton(
                        checked = debandMode == mode,
                        onCheckedChange = {
                            decoderPreferences.videoDebanding().set(mode)
                            // ANZ -->
                            applyDebandMode(mpv, mode, decoderPreferences)
                            // ANZ <--
                        }
                    ) {
                        when (mode) {
                            Debanding.None -> Icon(Icons.Default.NotInterested, null)
                            Debanding.CPU -> Icon(Icons.Default.Memory, null)
                            Debanding.GPU -> Icon(painterResource(R.drawable.expansion_card), null)
                        }
                    }
                }
                
                Text(text = stringResource(debandMode.titleRes))
                
                Spacer(Modifier.weight(1f))
                
                TextButton(onClick = {
                    decoderPreferences.videoDebanding().set(Debanding.None)
                    // ANZ -->
                    applyDebandMode(mpv, Debanding.None, decoderPreferences)
                    DebandSettings.entries.forEach { setting ->
                        val pref = setting.preference(decoderPreferences)
                        pref.delete()
                        mpv?.setPropertyInt(setting.mpvProperty, pref.get())
                    }
                    // ANZ <--
                }) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.reset_iso_24px), null)
                        Text(stringResource(MR.strings.action_reset))
                    }
                }
            }

            AnimatedVisibility(visible = debandMode == Debanding.GPU) {
                Column {
                    DebandSettings.entries.forEach { setting ->
                        key("deband-setting-${setting.name}") {
                            val value by setting.preference(decoderPreferences).collectAsState()
                            SliderItem(
                                label = stringResource(setting.titleRes),
                                value = value,
                                valueText = value.toString(),
                                onChange = {
                                    setting.preference(decoderPreferences).set(it)
                                    // ANZ -->
                                    applyDebandSetting(mpv, setting, it)
                                    // ANZ <--
                                },
                                max = setting.end,
                                min = setting.start,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Anime4KCard() {
    val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
    val anime4kManager = remember { Injekt.get<Anime4KManager>() }
    val enableAnime4K by decoderPreferences.enableAnime4K().collectAsState()
    var isExpanded by remember { mutableStateOf(false) }
    // ANZ -->
    val activity = LocalContext.current as? PlayerActivity
    val mpv = activity?.viewModel?.mpv
    // ANZ <--

    ExpandableCard(
        isExpanded = isExpanded,
        onExpand = { isExpanded = it },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                Icon(Icons.Default.Memory, null)
                Text(stringResource(MR.strings.pref_anime4k_title))
            }
        },
        colors = panelCardsColors(),
    ) {
        Column(
            modifier = Modifier
                .padding(MaterialTheme.padding.medium)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(MR.strings.pref_anime4k_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = enableAnime4K,
                    onCheckedChange = {
                        decoderPreferences.enableAnime4K().set(it)
                        // ANZ -->
                        applyAnime4K(mpv, decoderPreferences, anime4kManager)
                        // ANZ <--
                    }
                )
            }

            if (enableAnime4K) {
                val anime4kMode by decoderPreferences.anime4kMode().collectAsState()
                val anime4kQuality by decoderPreferences.anime4kQuality().collectAsState()

                Text(
                    text = stringResource(MR.strings.pref_anime4k_mode),
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    itemsIndexed(
                        items = Anime4KManager.Mode.entries,
                        key = { index, it -> "a4k-m-$index-${it.name}" }
                    ) { _, mode ->
                        if (mode == Anime4KManager.Mode.OFF) return@itemsIndexed
                        InputChip(
                            selected = anime4kMode == mode.name,
                            onClick = {
                                decoderPreferences.anime4kMode().set(mode.name)
                                // ANZ -->
                                applyAnime4K(mpv, decoderPreferences, anime4kManager)
                                // ANZ <--
                            },
                            label = { Text(mode.name.replace("_", "+")) },
                        )
                    }
                }

                Text(
                    text = stringResource(MR.strings.pref_anime4k_quality),
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    itemsIndexed(
                        items = Anime4KManager.Quality.entries,
                        key = { index, it -> "a4k-q-$index-${it.name}" }
                    ) { _, quality ->
                        val label = when (quality) {
                            Anime4KManager.Quality.FAST -> stringResource(MR.strings.anime4k_quality_fast)
                            Anime4KManager.Quality.BALANCED -> stringResource(MR.strings.anime4k_quality_balanced)
                            Anime4KManager.Quality.HIGH -> stringResource(MR.strings.anime4k_quality_high)
                        }
                        InputChip(
                            selected = anime4kQuality == quality.name,
                            onClick = {
                                decoderPreferences.anime4kQuality().set(quality.name)
                                // ANZ -->
                                applyAnime4K(mpv, decoderPreferences, anime4kManager)
                                // ANZ <--
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}

// ANZ -->
@Composable
fun InterpolationCard() {
    val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
    val smoothMotion by decoderPreferences.smoothMotion().collectAsState()
    val currentMode by decoderPreferences.interpolationMode().collectAsState()
    val fpsLimit by decoderPreferences.interpolationFPSLimit().collectAsState()
    var isExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context.findActivity() as? PlayerActivity
    val mpv = activity?.viewModel?.mpv

    ExpandableCard(
        isExpanded = isExpanded,
        onExpand = { isExpanded = it },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                Icon(Icons.Outlined.Speed, null)
                Text(stringResource(AMR.strings.player_sheets_interpolation_title))
            }
        },
        colors = panelCardsColors(),
    ) {
        Column(
            modifier = Modifier
                .padding(MaterialTheme.padding.medium)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(AMR.strings.player_sheets_interpolation_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = smoothMotion,
                    onCheckedChange = {
                        decoderPreferences.smoothMotion().set(it)
                        applyInterpolation(mpv, decoderPreferences, context)
                    },
                )
            }

            if (smoothMotion) {
                Text(
                    text = stringResource(AMR.strings.pref_interpolation_profile),
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    itemsIndexed(
                        items = InterpolationMode.entries,
                        key = { _, it -> "vf-interp-${it.name}" },
                    ) { _, mode ->
                        InputChip(
                            selected = currentMode == mode,
                            onClick = {
                                decoderPreferences.interpolationMode().set(mode)
                                applyInterpolation(mpv, decoderPreferences, context)
                            },
                            label = { Text(mode.title) },
                        )
                    }
                }

                val fpsSteps = remember { listOf(0, 30, 60, 90, 120, 144) }
                val currentStepIndex = remember(fpsLimit) {
                    val idx = fpsSteps.indexOf(fpsLimit)
                    if (idx != -1) {
                        idx
                    } else {
                        fpsSteps.indices.minByOrNull { kotlin.math.abs(fpsSteps[it] - fpsLimit) } ?: 0
                    }
                }

                val fpsText = if (fpsLimit == 0) {
                    "Auto"
                } else {
                    "$fpsLimit FPS"
                }

                SliderItem(
                    label = stringResource(AMR.strings.pref_interpolation_target_fps),
                    value = currentStepIndex.toFloat(),
                    valueText = fpsText,
                    onChange = {
                        val chosenIndex = it.roundToInt().coerceIn(0, fpsSteps.lastIndex)
                        val chosenFps = fpsSteps[chosenIndex]
                        decoderPreferences.interpolationFPSLimit().set(chosenFps)
                        applyInterpolation(mpv, decoderPreferences, context)
                    },
                    min = 0f,
                    max = (fpsSteps.size - 1).toFloat(),
                    steps = fpsSteps.size - 2,
                )
            }
        }
    }
}
// ANZ <--
