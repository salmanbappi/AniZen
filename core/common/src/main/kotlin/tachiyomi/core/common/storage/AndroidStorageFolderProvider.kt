package tachiyomi.core.common.storage

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

class AndroidStorageFolderProvider(
    private val context: Context,
) : FolderProvider {

    // ANZ -->
    override fun directory(): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }
    // ANZ <--

    override fun path(): String {
        return directory().toUri().toString()
    }
}
