package mihon.domain.extensionrepo.interactor

import logcat.LogPriority
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.KEIYOUSHI_SIGNATURE
import mihon.domain.extensionrepo.model.SALMANBAPPI_SIGNATURE
import mihon.domain.extensionrepo.model.YUZONO_SIGNATURE
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat

class CreateExtensionRepo(
    private val repository: ExtensionRepoRepository,
    private val service: ExtensionRepoService,
) {
    suspend fun await(indexUrl: String): Result {
        // ANZ -->
        val trimmed = indexUrl.trim().removeSuffix("/")
        val httpUrl = trimmed.toHttpUrlOrNull()
            ?: return Result.InvalidUrl

        if (httpUrl.scheme != "https" && httpUrl.scheme != "http") {
            return Result.InvalidUrl
        }

        return service.fetchRepoDetails(trimmed, forceNetwork = true)?.let { insert(it) } ?: Result.InvalidUrl
        // ANZ <--
    }

    private suspend fun insert(repo: ExtensionRepo): Result {
        return try {
            repository.insertRepo(
                repo.baseUrl,
                repo.name,
                repo.shortName,
                repo.website,
                repo.signingKeyFingerprint,
                isVisible = true,
                author = repo.author,
                discord = repo.discord,
                icon = repo.icon,
                // ANZ -->
                isLegacy = repo.isLegacy,
                extensionListUrl = repo.extensionListUrl,
                // ANZ <--
            )
            Result.Success
        } catch (e: SaveExtensionRepoException) {
            logcat(LogPriority.WARN, e) { "SQL Conflict attempting to add new repository ${repo.baseUrl}" }
            return handleInsertionError(repo)
        }
    }

    /**
     * Error handler for insert when trying to create new repositories.
     *
     * SaveExtensionRepoException doesn't provide constraint info in exceptions.
     * Check if the conflict was on the primary key (baseUrl); if so, return RepoAlreadyExists.
     */
    private suspend fun handleInsertionError(repo: ExtensionRepo): Result {
        val repoExists = repository.getRepo(repo.baseUrl)
        if (repoExists != null) {
            return Result.RepoAlreadyExists
        }
        return Result.Error
    }

    sealed interface Result {
        data object InvalidUrl : Result
        data object RepoAlreadyExists : Result
        data object Success : Result
        data object Error : Result
    }

    companion object {
        const val OFFICIAL_REPO_WEBSITE = "https://github.com/salmanbappi/AniZen"
        const val OFFICIAL_REPO_BASE_URL = "https://raw.githubusercontent.com/anizen-app/extensions/repo"

        const val OFFICIAL_REPO_SIGNATURE = YUZONO_SIGNATURE
        const val KEIYOUSHI_REPO_SIGNATURE = KEIYOUSHI_SIGNATURE
        const val SALMANBAPPI_REPO_SIGNATURE = SALMANBAPPI_SIGNATURE
    }
}
