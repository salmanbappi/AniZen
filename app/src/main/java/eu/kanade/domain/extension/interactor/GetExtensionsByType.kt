package eu.kanade.domain.extension.interactor

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.ContentWarningLevel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository

class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val extensionRepoRepository: ExtensionRepoRepository,
) {

    fun subscribe(): Flow<Extensions> {
        return combine(
            preferences.enabledLanguages().changes(),
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
            extensionRepoRepository.subscribeAll(),
            extensionManager.isInitialized,
            preferences.contentWarningLevel().changes(),
        ) { flows ->
            val enabledLanguages = flows[0] as Set<String>
            val _installed = flows[1] as List<Extension.Installed>
            val _untrusted = flows[2] as List<Extension.Untrusted>
            val _available = flows[3] as List<Extension.Available>
            val repos = flows[4] as List<mihon.domain.extensionrepo.model.ExtensionRepo>
            val isInitialized = flows[5] as Boolean
            val contentWarningLevel = flows[6] as ContentWarningLevel

            if (!isInitialized) return@combine null

            val (updates, installed) = _installed
                .filter { contentWarningLevel.allowsInstalled(it.contentWarning) }
                .sortedWith(
                    compareBy<Extension.Installed> { !it.isObsolete }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .partition { it.hasUpdate }

            val untrusted = _untrusted
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            val hiddenRepos = repos.filter { !it.isVisible }.map { it.baseUrl.removeSuffix("/") }

            val available = _available
                .filter { extension ->
                    _installed.none { it.pkgName == extension.pkgName && it.author == extension.author } &&
                        _untrusted.none { it.pkgName == extension.pkgName && it.author == extension.author } &&
                        contentWarningLevel.allowsDiscovery(extension.contentWarning) &&
                        hiddenRepos.none { extension.repoUrl.startsWith(it) }
                }
                .flatMap { ext ->
                    if (ext.sources.isEmpty()) {
                        return@flatMap if (ext.lang in enabledLanguages) listOf(ext) else emptyList()
                    }
                    ext.sources.filter { it.lang in enabledLanguages }
                        .map {
                            ext.copy(
                                name = it.name,
                                lang = it.lang,
                                pkgName = "${ext.pkgName}-${it.id}",
                                sources = listOf(it),
                            )
                        }
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            Extensions(updates, installed, available, untrusted)
        }
        .filterNotNull()
    }
}
