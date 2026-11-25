package mihon.domain.extensionrepo.interactor

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import tachiyomi.core.common.util.system.logcat

class UpdateExtensionRepo(
    private val repository: ExtensionRepoRepository,
    private val service: ExtensionRepoService,
) {

    suspend fun awaitAll() = coroutineScope {
        repository.getAll()
            .map { async { await(it) } }
            .awaitAll()
    }

    suspend fun await(repo: ExtensionRepo) {
        val newRepo = service.fetchRepoDetails(repo.baseUrl, forceNetwork = true) ?: return
        val existingFingerprint = repo.signingKeyFingerprint.trim().lowercase().padStart(64, '0')
        if (
            repo.signingKeyFingerprint.startsWith("NOFINGERPRINT") ||
            existingFingerprint == newRepo.signingKeyFingerprint
        ) {
            try {
                repository.updateRepo(
                    oldBaseUrl = repo.baseUrl,
                    newRepo = newRepo.copy(
                        isVisible = repo.isVisible,
                        author = newRepo.author ?: repo.author,
                        discord = newRepo.discord ?: repo.discord,
                        icon = newRepo.icon ?: repo.icon,
                    ),
                )
            } catch (e: SaveExtensionRepoException) {
                logcat(LogPriority.ERROR, e) { "Failed to update extension repo ${repo.baseUrl}" }
            }
        }
    }
}
