package mihon.domain.extensionrepo.interactor

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizeSigningKey
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

class UpdateExtensionRepo(
    private val repository: ExtensionRepoRepository,
    private val service: ExtensionRepoService,
) {

    suspend fun awaitAll() = supervisorScope {
        val updates = repository.getAll()
            .map { repo ->
                async {
                    try {
                        fetchUpdatedRepo(repo)?.let { repo.baseUrl to it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to refresh extension repo ${repo.baseUrl}" }
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        if (updates.isNotEmpty()) {
            try {
                repository.updateRepos(updates)
            } catch (e: SaveExtensionRepoException) {
                logcat(LogPriority.ERROR, e) { "Failed to save refreshed extension repos" }
            }
        }
    }

    suspend fun await(repo: ExtensionRepo) {
        val updated = fetchUpdatedRepo(repo) ?: return
        try {
            repository.updateRepo(repo.baseUrl, updated)
        } catch (e: SaveExtensionRepoException) {
            logcat(LogPriority.ERROR, e) { "Failed to update extension repo ${repo.baseUrl}" }
        }
    }

    private suspend fun fetchUpdatedRepo(repo: ExtensionRepo): ExtensionRepo? {
        val newRepo = service.fetchRepoDetails(repo.baseUrl, forceNetwork = true) ?: return null
        val existingFingerprint = normalizeSigningKey(repo.signingKeyFingerprint)
        val newFingerprint = normalizeSigningKey(newRepo.signingKeyFingerprint)
        if (existingFingerprint != newFingerprint) {
            return null
        }
        return newRepo.copy(
            isVisible = repo.isVisible,
            author = newRepo.author ?: repo.author,
            discord = newRepo.discord ?: repo.discord,
            icon = newRepo.icon ?: repo.icon,
        )
    }
}
