package eu.kanade.presentation.browse.components

import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.InstalledExtensionIcon
import eu.kanade.tachiyomi.data.coil.InstalledSourceIcon
import eu.kanade.tachiyomi.extension.model.Extension
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
    when {
        source.isStub -> {
            Image(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
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
            // ANZ -->
            val defaultIcon = painterResource(R.mipmap.ic_default_source) // ANZ
            AsyncImage(
                model = InstalledSourceIcon(source.id),
                contentDescription = null,
                placeholder = defaultIcon,
                error = defaultIcon,
                modifier = modifier.then(defaultModifier),
            )
            // ANZ <--
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
            // ANZ -->
            val context = LocalContext.current
            AsyncImage(
                model = extension.iconUrl,
                contentDescription = null,
                placeholder = ColorPainter(Color(0x1F888888)),
                error = eu.kanade.presentation.anime.components.CoverErrorPainter.get(context),
                modifier = modifier
                    .clip(MaterialTheme.shapes.extraSmall),
            )
            // ANZ <--
        }
        is Extension.Installed -> {
            // ANZ -->
            val defaultIcon = painterResource(R.mipmap.ic_default_source) // ANZ
            AsyncImage(
                model = InstalledExtensionIcon(extension.pkgName, extension.versionCode, density),
                contentDescription = null,
                placeholder = defaultIcon,
                error = defaultIcon,
                modifier = modifier,
            )
            // ANZ <--
        }
        is Extension.Untrusted -> Image(
            imageVector = Icons.Filled.Dangerous,
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
            modifier = modifier.then(defaultModifier),
        )
    }
}
