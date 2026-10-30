package eu.kanade.domain.source.model

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.extension.ExtensionManager
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANZ -->
private val NULL_ICON_SENTINEL = Any()
private val iconBitmapCache = java.util.concurrent.ConcurrentHashMap<Long, Any>() // ANZ

val Source.icon: ImageBitmap?
    get() {
        val cached = iconBitmapCache.getOrPut(id) {
            Injekt.get<ExtensionManager>().getAppIconForSource(id)
                ?.toBitmap()
                ?.asImageBitmap()
                ?: NULL_ICON_SENTINEL
        }
        return if (cached === NULL_ICON_SENTINEL) null else cached as ImageBitmap
    }
// ANZ <--
