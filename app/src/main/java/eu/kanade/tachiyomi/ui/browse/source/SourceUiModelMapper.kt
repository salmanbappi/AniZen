package eu.kanade.tachiyomi.ui.browse.source

import android.app.Application
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.model.NodeStatus
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceUiModelMapper(
    private val context: Application = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) {
    fun map(
        source: Source,
        headerKey: String = "",
        isNsfw: Boolean = source.isNsfw,
        status: NodeStatus? = null,
    ): SourceUiModel.Item {
        val extensionName = extensionManager.getExtensionNameForSource(source.id)
        val sourceLangString = LocaleHelper.getSourceDisplayName(source.lang, context)
        val nameLower = source.name.lowercase()
        val isBdix = false
        
        val sourceClass = source.javaClass.simpleName
        val isApi = nameLower.contains("api") || 
                    nameLower.contains("json") || 
                    sourceClass.contains("Api") || 
                    sourceClass.contains("Json")

        val secondaryText = buildString {
            append(sourceLangString)
            if (extensionName != null && extensionName != source.name) {
                append(" • ")
                append(extensionName)
            }
        }

        return SourceUiModel.Item(
            source = source,
            headerKey = headerKey,
            isNsfw = isNsfw,
            status = status,
            isBdix = isBdix,
            isApi = isApi,
            isStub = source.isStub,
            displayName = source.name.ifBlank { source.id.toString() },
            secondaryText = secondaryText,
            isTorrent = extensionManager.isTorrentSource(source.id),
        )
    }

    fun mapHeader(lang: String): SourceUiModel.Header {
        return SourceUiModel.Header(
            language = lang,
            displayName = LocaleHelper.getSourceDisplayName(lang, context),
        )
    }
}
