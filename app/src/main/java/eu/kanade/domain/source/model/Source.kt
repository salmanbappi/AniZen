package eu.kanade.domain.source.model

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.extension.ExtensionManager
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANZ -->
private val iconBitmapCache = java.util.concurrent.ConcurrentHashMap<Long, ImageBitmap>() // ANZ

val Source.icon: ImageBitmap?
    get() {
        return iconBitmapCache.getOrPut(id) {
            Injekt.get<ExtensionManager>().getAppIconForSource(id)
                ?.toBitmap()
                ?.asImageBitmap()
                ?: return null
        }
    }
// ANZ <--
