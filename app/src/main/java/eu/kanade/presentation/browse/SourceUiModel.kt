package eu.kanade.presentation.browse

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.network.model.NodeStatus
import tachiyomi.domain.source.model.Source

@Immutable
sealed interface SourceUiModel {
    @Immutable
    data class Item(
        val source: tachiyomi.domain.source.model.Source,
        val headerKey: String,
        val isNsfw: Boolean,
        val status: NodeStatus?,
        val isBdix: Boolean,
        val isApi: Boolean,
        val isStub: Boolean,
        val secondaryText: String,
        val displayName: String,
        val isTorrent: Boolean = false,
    ) : SourceUiModel

    @Immutable
    data class Header(val language: String, val displayName: String) : SourceUiModel
}
