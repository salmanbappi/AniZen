package eu.kanade.tachiyomi.ui.home

import android.content.Context
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.util.system.networkStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HomeScreenModel(
    context: Context,
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : ScreenModel {

    val adaptiveEngine = NavAdaptiveEngine(context, screenModelScope)

    val updatesCount: StateFlow<Int> = combine(
        libraryPreferences.newUpdatesCount().changes(),
        libraryPreferences.newMangaUpdatesCount().changes(),
        libraryPreferences.newShowUpdatesCount().changes(),
    ) { countAnime, countManga, showUpdates ->
        if (showUpdates) countAnime + countManga else 0
    }.stateIn(
        screenModelScope,
        SharingStarted.WhileSubscribed(5000),
        if (libraryPreferences.newShowUpdatesCount().get()) {
            libraryPreferences.newUpdatesCount().get() + libraryPreferences.newMangaUpdatesCount().get()
        } else {
            0
        },
    )

    val extensionUpdatesCount: StateFlow<Int> = sourcePreferences.animeExtensionUpdatesCount()
        .changes()
        .stateIn(
            screenModelScope,
            SharingStarted.WhileSubscribed(5000),
            sourcePreferences.animeExtensionUpdatesCount().get(),
        )

    init {
        // Polling evaluation (cooldown enforced, dispatched on IO to prevent main-thread jank)
        screenModelScope.launch(Dispatchers.IO) {
            while (true) {
                adaptiveEngine.evaluateRules()
                delay(60000) // Every minute
            }
        }

        // Real-time triggers (force bypasses cooldown for state changes, dispatched on IO)
        screenModelScope.launch(Dispatchers.IO) {
            merge(
                context.networkStateFlow(),
                uiPreferences.adaptiveNavEnabled().changes(),
                uiPreferences.adaptiveConnectivityRule().changes(),
                uiPreferences.adaptiveTimeRule().changes()
            ).collectLatest {
                adaptiveEngine.evaluateRules(force = true)
            }
        }
    }
}
