package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.model.normalizeSigningKey
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionRepoRestorer(
    private val animeHandler: DatabaseHandler = Injekt.get(),
    private val getExtensionRepos: GetExtensionRepo = Injekt.get(),
) {

    suspend operator fun invoke(
        backupRepo: BackupExtensionRepos,
    ) {
        val dbRepos = getExtensionRepos.getAll()
        val urlExists = dbRepos.firstOrNull { it.baseUrl == backupRepo.baseUrl }
        if (urlExists != null) {
            if (normalizeSigningKey(urlExists.signingKeyFingerprint) != normalizeSigningKey(backupRepo.signingKeyFingerprint)) {
                error("Already Exists with different signing key fingerprint")
            }
            return
        }
        animeHandler.await {
            extension_reposQueries.insert(
                base_url = backupRepo.baseUrl,
                name = backupRepo.name,
                short_name = backupRepo.shortName,
                website = backupRepo.website,
                fingerprint = normalizeSigningKey(backupRepo.signingKeyFingerprint),
                is_visible = backupRepo.isVisible,
                author = backupRepo.author,
                discord = backupRepo.discord,
                icon = backupRepo.icon,
                // ANZ -->
                is_legacy = backupRepo.isLegacy,
                extension_list_url = backupRepo.extensionListUrl,
                // ANZ <--
            )
        }
    }
}
