package eu.kanade.tachiyomi.extension

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizeSigningKey
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * The manager of anime extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available anime extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every anime extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 *
 * @param context The application context.
 * @param preferences The application preferences.
 */
class ExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustExtension = Injekt.get(),
    private val extensionRepoRepository: mihon.domain.extensionrepo.repository.ExtensionRepoRepository = Injekt.get(),
) {

    val scope = CoroutineScope(SupervisorJob())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * API where all the available anime extensions can be found.
     */
    private val api = ExtensionApi()

    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()

    @Volatile
    private var cachedRepos: List<ExtensionRepo> = emptyList()

    /**
     * The installer which installs, updates and uninstalls the anime extensions.
     */
    private val installer: ExtensionInstaller = Injekt.get()

    private val iconMap = mutableMapOf<String, Drawable>()

    private val repoHostAuthorRegex = """^https://(?:raw\.githubusercontent\.com|codeberg\.org|gitlab\.com)/([^/]+)/.*""".toRegex()
    private val versionComparator = compareBy<Extension.Available> { it.versionCode }.thenBy { it.libVersion }

    private val installedExtensionsMapFlow = MutableStateFlow(emptyMap<String, Extension.Installed>())
    private val availableExtensionListFlow = MutableStateFlow<List<Extension.Available>>(emptyList())

    // ANZ -->
    private val _installedExtensionsFlow = MutableStateFlow<List<Extension.Installed>>(emptyList()) // ANZ
    val installedExtensionsFlow: StateFlow<List<Extension.Installed>> = _installedExtensionsFlow.asStateFlow()

    private fun enrichInstalledExtensions(
        installedMap: Map<String, Extension.Installed>,
        repos: List<ExtensionRepo>,
        available: List<Extension.Available>,
    ): List<Extension.Installed> {
        return installedMap.values.map { extension ->
            val matchingRepo = extension.pickRepo(repos, available)
            val author = matchingRepo?.author
                ?: matchingRepo?.let { repo ->
                    repoHostAuthorRegex.find(repo.baseUrl)?.let { match ->
                        "@${match.groupValues[1]}"
                    } ?: repo.shortName ?: repo.name
                } ?: extension.author

            extension.copy(
                author = author,
                repoUrl = extension.repoUrl ?: matchingRepo?.baseUrl,
            )
        }
    }
    // ANZ <--

    private val availableExtensionsMapFlow = MutableStateFlow(emptyMap<Pair<String, String>, Extension.Available>())
    val availableExtensionsFlow = availableExtensionsMapFlow.mapExtensions(scope)

    private val untrustedExtensionsMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionsMapFlow.mapExtensions(scope)

    init {
        scope.launch {
            initAnimeExtensions()
        }
        scope.launch {
            observeRepos()
        }
        ExtensionInstallReceiver(InstallationListener()).register(context)
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = installedExtensionsMapFlow.value.values
            .find { ext ->
                ext.sources.any { it.id == sourceId }
            }
            ?.pkgName
            ?: return null

        return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
            ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                .loadIcon(context.packageManager)
        }
    }

    // ANZ -->
    fun getExtensionPackage(sourceId: Long): String? { // ANZ
        return installedExtensionsMapFlow.value.values
            .find { ext -> ext.sources.any { it.id == sourceId } }
            ?.pkgName
    }

    fun getExtensionContentWarning(sourceId: Long): eu.kanade.tachiyomi.extension.model.ContentWarning? {
        return installedExtensionsMapFlow.value.values
            .find { ext -> ext.sources.any { it.id == sourceId } }
            ?.contentWarning
    }
    // ANZ <--

    fun getExtensionNameForSource(sourceId: Long): String? {
        return installedExtensionsMapFlow.value.values
            .find { ext -> ext.sources.any { it.id == sourceId } }
            ?.name
    }

    fun isTorrentSource(sourceId: Long): Boolean {
        return installedExtensionsMapFlow.value.values
            .any { ext -> ext.isTorrent && ext.sources.any { it.id == sourceId } }
    }

    private var availableAnimeExtensionsSourcesData: Map<Long, StubSource> = emptyMap()

    private fun setupAvailableAnimeExtensionsSourcesDataMap(
        animeextensions: List<Extension.Available>,
    ) {
        if (animeextensions.isEmpty()) return
        availableAnimeExtensionsSourcesData = animeextensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableAnimeExtensionsSourcesData[id]

    /**
     * Loads and registers the installed animeextensions.
     */
    private suspend fun initAnimeExtensions() {
        val animeextensions = ExtensionLoader.loadExtensions(context)

        // ANZ -->
        if (cachedRepos.isEmpty()) { // ANZ
            cachedRepos = try {
                extensionRepoRepository.getAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        // ANZ <--

        installedExtensionsMapFlow.value = animeextensions
            .filterIsInstance<LoadResult.Success>()
            .associate { it.extension.pkgName to it.extension }

        untrustedExtensionsMapFlow.value = animeextensions
            .filterIsInstance<LoadResult.Untrusted>()
            .associate { it.extension.pkgName to it.extension }

        val currentAvailable = availableExtensionListFlow.value
        if (currentAvailable.isNotEmpty()) {
            updatedInstalledAnimeExtensionsStatuses(currentAvailable, cachedRepos)
        }

        // ANZ -->
        _installedExtensionsFlow.value = enrichInstalledExtensions(
            installedExtensionsMapFlow.value,
            cachedRepos,
            currentAvailable,
        )
        _isInitialized.value = true

        scope.launch {
            combine(
                installedExtensionsMapFlow,
                extensionRepoRepository.subscribeAll().onStart { emit(cachedRepos) },
                availableExtensionListFlow,
            ) { installedMap, repos, available ->
                enrichInstalledExtensions(installedMap, repos, available)
            }.collect { _installedExtensionsFlow.value = it }
        }
        // ANZ <--
    }

    private suspend fun observeRepos() {
        extensionRepoRepository.subscribeAll()
            .distinctUntilChanged()
            .drop(1)
            .collect { repos ->
                cachedRepos = repos

                val untrusted = untrustedExtensionsMapFlow.value.values
                if (untrusted.isNotEmpty()) {
                    val trustedKeys = repos.mapTo(HashSet()) { normalizeSigningKey(it.signingKeyFingerprint) }
                    val toPromote = untrusted.filter { ext ->
                        ext.signatures.any { normalizeSigningKey(it) in trustedKeys }
                    }
                    if (toPromote.isNotEmpty()) {
                        for (ext in toPromote) {
                            untrustedExtensionsMapFlow.value -= ext.pkgName
                            when (val result = ExtensionLoader.loadExtensionFromPkgName(context, ext.pkgName)) {
                                is LoadResult.Success -> registerNewExtension(result.extension.withUpdateCheck())
                                is LoadResult.Untrusted -> untrustedExtensionsMapFlow.value += result.extension
                                LoadResult.Error -> Unit
                            }
                        }
                        updatePendingUpdatesCount()
                    }
                }

                val currentList = availableExtensionListFlow.value
                if (currentList.isNotEmpty()) {
                    val activeRepoUrls = repos.mapTo(HashSet()) {
                        it.baseUrl.substringBefore("/index.min.json").removeSuffix("/")
                    }
                    val filtered = currentList.filter {
                        it.repoUrl.substringBefore("/index.min.json").removeSuffix("/") in activeRepoUrls
                    }
                    if (filtered.size != currentList.size) {
                        setAvailableExtensions(filtered, repos)
                    }
                }
            }
    }

    /**
     * Finds the available anime extensions in the [api] and updates [availableExtensionsMapFlow].
     */
    suspend fun findAvailableExtensions(forceNetwork: Boolean = false) {
        if (forceNetwork) {
            try {
                updateExtensionRepo.awaitAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to refresh extension repositories" }
            }
        }

        val repos = try {
            extensionRepoRepository.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cachedRepos
        }
        cachedRepos = repos

        val extensions: List<Extension.Available>? = try {
            api.findExtensions(forceNetwork = forceNetwork)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            null
        }

        if (extensions != null) {
            setAvailableExtensions(extensions, repos)
        }
    }

    private fun setAvailableExtensions(
        extensions: List<Extension.Available>,
        repos: List<ExtensionRepo>,
    ) {
        enableAdditionalSubLanguages(extensions)

        availableExtensionListFlow.value = extensions
        availableExtensionsMapFlow.value = extensions
            .groupBy { it.pkgName to normalizeSigningKey(it.signingKeyFingerprint) }
            .mapValues { (_, listings) -> listings.maxWith(versionComparator) }

        updatedInstalledAnimeExtensionsStatuses(extensions, repos)
        setupAvailableAnimeExtensionsSourcesDataMap(extensions)
    }

    /**
     * Refreshes repositories and available extensions, updates installed extension statuses,
     * and notifies the user if any updates are pending.
     */
    suspend fun checkForUpdates(): List<Extension.Installed> {
        isInitialized.first { it }
        return withIOContext {
            findAvailableExtensions(forceNetwork = true)
            val extensionsWithUpdate = installedExtensionsMapFlow.value.values.filter { it.hasUpdate }
            if (extensionsWithUpdate.isNotEmpty()) {
                ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
            }
            extensionsWithUpdate
        }
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(animeextensions: List<Extension.Available>) {
        if (subLanguagesEnabledOnFirstRun || animeextensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the animeextension level.
        val availableLanguages = animeextensions
            .flatMap(Extension.Available::sources)
            .distinctBy(Extension.Available.AnimeSource::lang)
            .map(Extension.Available.AnimeSource::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed animeextensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of animeextensions given by the [api].
     */
    private fun updatedInstalledAnimeExtensionsStatuses(
        availableExtensions: List<Extension.Available>,
        repos: List<ExtensionRepo> = cachedRepos,
    ) {
        if (repos.isEmpty()) {
            val installedExtensionsMap = installedExtensionsMapFlow.value.toMutableMap()
            var changed = false
            for ((pkgName, extension) in installedExtensionsMap) {
                val newExt = extension.copy(hasUpdate = false, isObsolete = false, repoUrl = null)
                if (newExt != extension) {
                    installedExtensionsMap[pkgName] = newExt
                    changed = true
                }
            }
            if (changed) {
                installedExtensionsMapFlow.value = installedExtensionsMap
            }
            availableAnimeExtensionsSourcesData = emptyMap()
            updatePendingUpdatesCount()
            return
        }

        if (availableExtensions.isEmpty()) {
            preferences.animeExtensionUpdatesCount().set(0)
            return
        }

        val installedExtensionsMap = installedExtensionsMapFlow.value.toMutableMap()
        var changed = false

        for ((pkgName, extension) in installedExtensionsMap) {
            val availableExt = extension.findListing(availableExtensions)

            if (availableExt == null) {
                val newExt = extension.copy(hasUpdate = false, isObsolete = true)
                if (newExt != extension) {
                    installedExtensionsMap[pkgName] = newExt
                    changed = true
                }
            } else {
                val hasUpdate = availableExt.isUpdateFor(extension)
                val newExt = extension.copy(
                    hasUpdate = hasUpdate,
                    isObsolete = false,
                    repoUrl = availableExt.repoUrl,
                )

                if (newExt != extension) {
                    installedExtensionsMap[pkgName] = newExt
                    changed = true
                }
            }
        }
        if (changed) {
            installedExtensionsMapFlow.value = installedExtensionsMap
        }
        updatePendingUpdatesCount()
    }

    /**
     * Returns a flow of the installation process for the given anime extension.
     It will complete
     * once the anime extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The anime extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(api.getApkUrl(extension), extension)
    }

    /**
     * Returns a flow of the installation process for the given anime extension. It will complete
     * once the anime extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The anime extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Flow<InstallStep> {
        val availableExt = extension.findListing(availableExtensionListFlow.value) ?: return emptyFlow()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        installer.cancelInstall(extension.pkgName)
    }

    /**
     * Sets to "installing" status of an anime extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the anime extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: Extension) {
        installer.uninstallApk(extension.pkgName)
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: Extension.Untrusted) {
        untrustedExtensionsMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionsMapFlow.value -= extension.pkgName

        ExtensionLoader.loadExtensionFromPkgName(context, extension.pkgName)
            .let { it as? LoadResult.Success }
            ?.let {
                registerNewExtension(it.extension.withUpdateCheck())
                updatePendingUpdatesCount()
            }
    }

    /**
     * Registers the given anime extension in this and the source managers.
     *
     * @param extension The anime extension to be registered.
     */
    private fun registerNewExtension(extension: Extension.Installed) {
        installedExtensionsMapFlow.value += extension
    }

    /**
     * Registers the given updated anime extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The anime extension to be registered.
     */
    private fun registerUpdatedExtension(extension: Extension.Installed) {
        installedExtensionsMapFlow.value += extension
    }

    /**
     * Unregisters the animeextension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterAnimeExtension(pkgName: String) {
        installedExtensionsMapFlow.value -= pkgName
        untrustedExtensionsMapFlow.value -= pkgName
    }

    /**
     * Listener which receives events of the anime extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: Extension.Installed) {
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
            installer.dismissInstallNotification(extension.pkgName)
        }

        override fun onExtensionUpdated(extension: Extension.Installed) {
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
            installer.dismissInstallNotification(extension.pkgName)
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            installedExtensionsMapFlow.value -= extension.pkgName
            untrustedExtensionsMapFlow.value += extension
            updatePendingUpdatesCount()
            installer.dismissInstallNotification(extension.pkgName)
        }

        override fun onPackageUninstalled(pkgName: String) {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            unregisterAnimeExtension(pkgName)
            updatePendingUpdatesCount()
            installer.dismissInstallNotification(pkgName)
        }
    }

    /**
     * Extension method to set the update field of an installed anime extension.
     */
    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        val available = availableExtensionListFlow.value
        if (available.isEmpty()) return this
        val availableExt = findListing(available)
            ?: return copy(hasUpdate = false, isObsolete = true)
        return copy(
            hasUpdate = availableExt.isUpdateFor(this),
            isObsolete = false,
            repoUrl = availableExt.repoUrl,
        )
    }

    private fun Extension.Installed.pickRepo(
        repos: List<ExtensionRepo>,
        available: List<Extension.Available>,
    ): ExtensionRepo? {
        val normalizedSignatures = signatures.mapTo(HashSet()) { normalizeSigningKey(it) }
        val signingRepos = repos.filter {
            normalizeSigningKey(it.signingKeyFingerprint) in normalizedSignatures
        }
        if (signingRepos.isEmpty()) return null

        val availableRepoUrls = available
            .asSequence()
            .filter { it.pkgName == pkgName }
            .mapTo(HashSet()) { it.repoUrl }

        return signingRepos.firstOrNull { it.baseUrl == repoUrl }
            ?: signingRepos.firstOrNull { it.baseUrl in availableRepoUrls }
            ?: signingRepos.firstOrNull()
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionsMapFlow.value.values.count { it.hasUpdate }
        preferences.animeExtensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : Extension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)

    private fun <K, T : Extension> StateFlow<Map<K, T>>.mapExtensions(
        scope: CoroutineScope,
    ): StateFlow<List<T>> {
        return map { it.values.toList() }.stateIn(scope, SharingStarted.Lazily, value.values.toList())
    }
}
