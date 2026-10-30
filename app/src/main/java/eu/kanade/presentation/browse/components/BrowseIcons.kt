package eu.kanade.presentation.browse.components

import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.domain.source.model.icon
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.source.localanime.isLocal

private val defaultModifier = Modifier
    .height(40.dp)
    .aspectRatio(1f)

@Composable
fun SourceIcon(
    source: Source,
    modifier: Modifier = Modifier,
) {
    val icon = source.icon

    when {
        source.isStub && icon == null -> {
            Image(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
                modifier = modifier.then(defaultModifier),
            )
        }
        icon != null -> {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        source.isLocal() -> {
            Image(
                painter = painterResource(R.mipmap.ic_local_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        else -> {
            Image(
                painter = painterResource(R.mipmap.ic_default_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
    }
}

@Composable
fun ExtensionIcon(
    extension: Extension,
    modifier: Modifier = Modifier,
    density: Int = DisplayMetrics.DENSITY_DEFAULT,
) {
    when (extension) {
        is Extension.Available -> {
            AsyncImage(
                model = extension.iconUrl,
                contentDescription = null,
                placeholder = ColorPainter(Color(0x1F888888)),
                error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                modifier = modifier
                    .clip(MaterialTheme.shapes.extraSmall),
            )
        }
        is Extension.Installed -> {
            val icon by extension.getIcon(density)
            when (icon) {
                is Result.Loading -> Box(modifier = modifier)
                is Result.Success -> Image(
                    bitmap = (icon as Result.Success<ImageBitmap>).value,
                    contentDescription = null,
                    modifier = modifier,
                )
                // ANZ -->
                is Result.Error -> {
                    val context = LocalContext.current
                    val defaultBitmap = remember(context) { getDefaultSourceBitmap(context) } // ANZ
                    Image(
                        bitmap = defaultBitmap,
                        contentDescription = null,
                        modifier = modifier,
                    )
                }
                // ANZ <--
            }
        }
        is Extension.Untrusted -> Image(
            imageVector = Icons.Filled.Dangerous,
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
            modifier = modifier.then(defaultModifier),
        )
    }
}

// ANZ -->
private val iconCache = androidx.collection.LruCache<String, Result<ImageBitmap>>(250)

// ANZ
@Volatile
private var defaultSourceBitmap: ImageBitmap? = null

private fun getDefaultSourceBitmap(context: Context): ImageBitmap { // ANZ
    return defaultSourceBitmap ?: synchronized(iconCache) {
        defaultSourceBitmap ?: ContextCompat.getDrawable(context, R.mipmap.ic_default_source)!!
            .toBitmap()
            .asImageBitmap()
            .also { defaultSourceBitmap = it }
    }
}

@Composable
private fun Extension.getIcon(density: Int = DisplayMetrics.DENSITY_DEFAULT): State<Result<ImageBitmap>> {
    val context = LocalContext.current
    return produceState<Result<ImageBitmap>>(initialValue = iconCache[pkgName] ?: Result.Loading, pkgName) { // ANZ
        if (value !is Result.Loading) return@produceState
        withIOContext {
            val fromCache = iconCache[pkgName]
            if (fromCache != null) {
                value = fromCache
                return@withIOContext
            }
            value = try {
                val appInfo = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                val appResources = context.packageManager.getResourcesForApplication(appInfo)
                val bitmap = appResources.getDrawableForDensity(appInfo.icon, density, null)!!
                    .toBitmap()
                    .asImageBitmap()
                val result = Result.Success(bitmap)
                iconCache.put(pkgName, result)
                result
            } catch (e: Exception) {
                iconCache.put(pkgName, Result.Error)
                Result.Error
            }
        }
    }
}
// ANZ <--

sealed class Result<out T> {
    data object Loading : Result<Nothing>()
    data object Error : Result<Nothing>()
    data class Success<out T>(val value: T) : Result<T>()
}
