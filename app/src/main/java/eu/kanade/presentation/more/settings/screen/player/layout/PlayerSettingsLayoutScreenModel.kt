package eu.kanade.presentation.more.settings.screen.player.layout

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.tachiyomi.ui.player.LayoutRegion
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.parseButtons
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PlayerSettingsLayoutScreenModel(
    private val playerPreferences: PlayerPreferences = Injekt.get(),
) : StateScreenModel<LayoutScreenState>(LayoutScreenState()) {

    init {
        loadRegion(state.value.selectedRegion)
    }

    fun selectRegion(region: LayoutRegion) {
        mutableState.update { it.copy(selectedRegion = region) }
        loadRegion(region)
    }

    private fun loadRegion(region: LayoutRegion) {
        val buttonsCsv = when (region) {
            LayoutRegion.TopRight -> playerPreferences.topRightControls().get()
            LayoutRegion.BottomLeft -> playerPreferences.bottomLeftControls().get()
            LayoutRegion.BottomRight -> playerPreferences.bottomRightControls().get()
            LayoutRegion.Portrait -> playerPreferences.portraitBottomControls().get()
        }
        val buttons = parseButtons(buttonsCsv)

        val disabledButtons = if (region == LayoutRegion.Portrait) {
            emptySet<PlayerButton>()
        } else {
            val otherRegions = LayoutRegion.entries.filter { it != region && it != LayoutRegion.Portrait }
            val otherButtons = otherRegions.flatMap { r ->
                val csv = when (r) {
                    LayoutRegion.TopRight -> playerPreferences.topRightControls().get()
                    LayoutRegion.BottomLeft -> playerPreferences.bottomLeftControls().get()
                    LayoutRegion.BottomRight -> playerPreferences.bottomRightControls().get()
                    LayoutRegion.Portrait -> ""
                }
                parseButtons(csv)
            }
            otherButtons.toSet()
        }

        mutableState.update {
            it.copy(
                buttons = buttons.toImmutableList(),
                disabledButtons = disabledButtons.toImmutableList(),
            )
        }
    }

    fun updateButtons(newButtons: List<PlayerButton>) {
        val csv = newButtons.joinToString(",") { it.name }
        when (state.value.selectedRegion) {
            LayoutRegion.TopRight -> playerPreferences.topRightControls().set(csv)
            LayoutRegion.BottomLeft -> playerPreferences.bottomLeftControls().set(csv)
            LayoutRegion.BottomRight -> playerPreferences.bottomRightControls().set(csv)
            LayoutRegion.Portrait -> playerPreferences.portraitBottomControls().set(csv)
        }
        mutableState.update { it.copy(buttons = newButtons.toImmutableList()) }
    }

    fun resetToDefault() {
        when (state.value.selectedRegion) {
            LayoutRegion.TopRight -> playerPreferences.topRightControls().delete()
            LayoutRegion.BottomLeft -> playerPreferences.bottomLeftControls().delete()
            LayoutRegion.BottomRight -> playerPreferences.bottomRightControls().delete()
            LayoutRegion.Portrait -> playerPreferences.portraitBottomControls().delete()
        }
        loadRegion(state.value.selectedRegion)
    }

    fun isCastEnabled(): Boolean {
        return playerPreferences.enableCast().get()
    }
}

@Immutable
data class LayoutScreenState(
    val selectedRegion: LayoutRegion = LayoutRegion.TopRight,
    val buttons: ImmutableList<PlayerButton> = emptyList<PlayerButton>().toImmutableList(),
    val disabledButtons: ImmutableList<PlayerButton> = emptyList<PlayerButton>().toImmutableList(),
)
