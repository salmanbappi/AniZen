package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.CustomButtonBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionRepoBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionsBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomButtons
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetFavorites
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val animeBackupCreator: AnimeBackupCreator = AnimeBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    private val extensionsBackupCreator: ExtensionsBackupCreator = ExtensionsBackupCreator(context),
    private val extensionRepoBackupCreator: ExtensionRepoBackupCreator = ExtensionRepoBackupCreator(),
    private val customButtonBackupCreator: CustomButtonBackupCreator = CustomButtonBackupCreator(),
) {

    suspend fun createBackup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                val numberOfBackups = maxOf(1, backupPreferences.numberOfBackups().get())
                dir?.listFiles()
                    ?.filter { it.isFile && it.name != null && FILENAME_REGEX.matches(it.name!!) }
                    ?.sortedByDescending { it.name }
                    ?.drop(numberOfBackups - 1)
                    ?.forEach { it.delete() }

                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.exists()) {
                throw Exception(context.stringResource(MR.strings.creating_backup_error))
            }

            val nonFavoriteAnime = if (options.seenEntries) {
                animeRepository.getSeenAnimeNotInLibrary()
            } else {
                emptyList()
            }
            val backupAnime = backupAnimes(getFavorites.await() + nonFavoriteAnime, options)
            val backupCategories = backupAnimeCategories(options)
            val backupSources = backupAnimeSources(backupAnime)
            val backupExtensions = backupExtensions(options)
            val backupExtensionRepo = backupAnimeExtensionRepos(options)
            val backupCustomButton = backupCustomButtons(options)

            val backup = Backup(
                backupPreferences = backupAppPreferences(options),
                backupSourcePreferences = backupSourcePreferences(options),

                isLegacy = false,
                backupAnime = backupAnime,
                backupCategories = backupCategories,
                backupSources = backupSources,
                backupExtensions = backupExtensions,
                backupExtensionRepo = backupExtensionRepo,
                backupCustomButton = backupCustomButton,
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream().use {
                it.sink().gzip().buffer().use { gz ->
                    gz.write(byteArray)
                }
            }

            return file.uri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    suspend fun backupAnimes(anime: List<tachiyomi.domain.anime.model.Anime>, options: BackupOptions): List<BackupAnime> {
        return animeBackupCreator(anime, options)
    }

    suspend fun backupAnimeCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()
        return categoriesBackupCreator()
    }

    fun backupAnimeSources(animes: List<BackupAnime>): List<BackupSource> {
        return sourcesBackupCreator(animes)
    }

    fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()
        return preferenceBackupCreator.createApp(options.privateSettings)
    }

    fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()
        return preferenceBackupCreator.createSource(options.privateSettings)
    }

    suspend fun backupAnimeExtensionRepos(options: BackupOptions): List<BackupExtensionRepos> {
        if (!options.extensionRepoSettings) return emptyList()
        return extensionRepoBackupCreator()
    }

    suspend fun backupCustomButtons(options: BackupOptions): List<BackupCustomButtons> {
        if (!options.customButton) return emptyList()
        return customButtonBackupCreator()
    }

    fun backupExtensions(options: BackupOptions): List<BackupExtension> {
        if (!options.extensions) return emptyList()

        return extensionsBackupCreator()
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
