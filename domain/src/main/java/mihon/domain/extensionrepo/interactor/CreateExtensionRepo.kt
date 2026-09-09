package mihon.domain.extensionrepo.interactor

import logcat.LogPriority
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.KEIYOUSHI_SIGNATURE
import mihon.domain.extensionrepo.model.ExtensionRepo
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
        val trimmed = indexUrl.trim().removeSuffix("/")
        val baseUrl = when {
            trimmed.endsWith("/index.min.json") -> trimmed.removeSuffix("/index.min.json")
            trimmed.endsWith("/repo.json") -> trimmed.removeSuffix("/repo.json")
            else -> trimmed
        }

        val httpUrl = baseUrl.toHttpUrlOrNull()
            ?: return Result.InvalidUrl

        if (httpUrl.scheme != "https" && httpUrl.scheme != "http") {
            return Result.InvalidUrl
        }

        return service.fetchRepoDetails(baseUrl)?.let { insert(it) } ?: Result.InvalidUrl
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
            )
            Result.Success
        } catch (e: SaveExtensionRepoException) {
            logcat(LogPriority.WARN, e) { "SQL Conflict attempting to add new repository ${repo.baseUrl}" }
            return handleInsertionError(repo)
        }
    }

    /**
     * Error Handler for insert when there are trying to create new repositories
     *
     * SaveExtensionRepoException doesn't provide constraint info in exceptions.
     * First check if the conflict was on primary key. if so return RepoAlreadyExists
     * Then check if the conflict was on fingerprint. if so Return DuplicateFingerprint
     * If neither are found, there was some other Error, and return Result.Error
     *
     * @param repo Extension Repo holder for passing to DB/Error Dialog
     */
    private suspend fun handleInsertionError(repo: ExtensionRepo): Result {
        val repoExists = repository.getRepo(repo.baseUrl)
        if (repoExists != null) {
            return Result.RepoAlreadyExists
        }
        val matchingFingerprintRepo = repository.getRepoBySigningKeyFingerprint(repo.signingKeyFingerprint)
            ?: repository.getRepoBySigningKeyFingerprint(repo.signingKeyFingerprint.removePrefix("0"))
        if (matchingFingerprintRepo != null) {
            return Result.DuplicateFingerprint(matchingFingerprintRepo, repo)
        }
        return Result.Error
    }

    sealed interface Result {
        data class DuplicateFingerprint(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : Result
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
