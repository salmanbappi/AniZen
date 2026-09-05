package eu.kanade.presentation.anime.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.domain.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Cover-related app preferences, mirrored into snapshot state.
 *
 * Every cover composable needs these, and there can be dozens on screen. Reading them
 * through `Injekt.get<UiPreferences>().foo().get()` per item cost a DI lookup plus a
 * SharedPreferences read *per cover*, and wrapping that in `remember {}` (as the cover code
 * used to) also meant a settings change was not picked up until the composable happened to
 * be recreated.
 *
 * Mirroring the two flags here fixes both: reads are a plain snapshot-state read, and a
 * settings change invalidates exactly the composables that read the flag.
 *
 * [init] is called once from `App.onCreate`. The initial values match the preference
 * defaults so covers render correctly even if something reads them before that.
 */
object CoverSettings {

    /** `UiPreferences.animatedTransitions()` — crossfade + loading skeleton. */
    var animatedTransitions by mutableStateOf(true)
        private set

    /** `UiPreferences.panoramaCover()` — allow wide covers to use the panorama aspect. */
    var panoramaCover by mutableStateOf(false)
        private set

    fun init(uiPreferences: UiPreferences, scope: CoroutineScope) {
        uiPreferences.animatedTransitions().changes()
            .onEach { animatedTransitions = it }
            .launchIn(scope)
        uiPreferences.panoramaCover().changes()
            .onEach { panoramaCover = it }
            .launchIn(scope)
    }
}
