package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap

class StorageManager(
    private val context: Context,
    // ANZ -->
    private val storagePreferences: StoragePreferences, // ANZ
    // ANZ <--
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    // ANZ -->
    private val baseDirLock = Any()
    private val childDirectories = ConcurrentHashMap<String, Pair<UniFile, UniFile>>()

    @Volatile
    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory().get())
    // ANZ <--

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                // ANZ -->
                val resolvedBaseDir = synchronized(baseDirLock) {
                    // ANZ
                    childDirectories.clear()
                    getBaseDir(uri).also { baseDir = it }
                }
                // ANZ <--
                resolvedBaseDir?.let { parent ->
                    // ANZ
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    parent.createDirectory(MPV_CONFIG_PATH)?.let { mpvDir ->
                        mpvDir.createDirectory(FONTS_PATH)
                        mpvDir.createDirectory(SCRIPTS_PATH)
                        mpvDir.createDirectory(SCRIPT_OPTS_PATH)
                        // ANZ -->
                        mpvDir.createDirectory(SHADERS_PATH)
                        // ANZ <--
                    }
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    // ANZ -->
    private fun getOrResolveBaseDir(): UniFile? {
        baseDir?.let { return it }
        return synchronized(baseDirLock) {
            // ANZ
            baseDir ?: getBaseDir(storagePreferences.baseStorageDirectory().get()).also {
                baseDir = it
            }
        }
    }

    private fun ensureSubdirectory(name: String): UniFile? {
        val parent = getOrResolveBaseDir()
        if (parent != null) {
            val cached = childDirectories[name]
            if (cached != null && cached.first === parent && cached.second.exists()) {
                return cached.second
            }
            parent.createDirectory(name)?.let {
                synchronized(baseDirLock) {
                    // ANZ
                    if (baseDir === parent) {
                        childDirectories[name] = parent to it
                    }
                }
                return it
            }
        }
        return synchronized(baseDirLock) {
            childDirectories.clear()
            val refreshed = getBaseDir(storagePreferences.baseStorageDirectory().get())
            baseDir = refreshed
            refreshed?.createDirectory(name)?.also {
                childDirectories[name] = refreshed to it
            }
        }
    }
    // ANZ <--

    fun getAutomaticBackupsDirectory(): UniFile? {
        // ANZ -->
        return ensureSubdirectory(AUTOMATIC_BACKUPS_PATH)
        // ANZ <--
    }

    fun getDownloadsDirectory(): UniFile? {
        // ANZ -->
        return ensureSubdirectory(DOWNLOADS_PATH)
        // ANZ <--
    }

    fun getLocalSourceDirectory(): UniFile? {
        // ANZ -->
        return ensureSubdirectory(LOCAL_SOURCE_PATH)
        // ANZ <--
    }

    fun getFontsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(FONTS_PATH)
    }

    fun getScriptsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(SCRIPTS_PATH)
    }

    fun getScriptOptsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(SCRIPT_OPTS_PATH)
    }

    // ANZ -->
    fun getShadersDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(SHADERS_PATH)
    }
    // ANZ <--

    fun getMPVConfigDirectory(): UniFile? {
        // ANZ -->
        return ensureSubdirectory(MPV_CONFIG_PATH)
        // ANZ <--
    }

    // SY -->
    fun getLogsDirectory(): UniFile? {
        // ANZ -->
        return ensureSubdirectory(LOGS_PATH)
        // ANZ <--
    }
    // SY <--
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "localanime"
private const val MPV_CONFIG_PATH = "mpv-config"
private const val FONTS_PATH = "fonts"
const val SCRIPTS_PATH = "scripts"
const val SCRIPT_OPTS_PATH = "script-opts"

// ANZ -->
const val SHADERS_PATH = "shaders"
// ANZ <--

// SY -->
private const val LOGS_PATH = "logs"
// SY <--
