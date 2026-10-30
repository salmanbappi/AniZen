package eu.kanade.presentation.library.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.murgupluoglu.flagkit.FlagKit
import eu.kanade.domain.source.model.icon
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.source.localanime.isLocal

@Composable
internal fun SourceIconBadge(
    source: Source?,
) {
    if (source == null) return
    val icon = source.icon

    when {
        source.isStub && icon == null -> {
            Badge(
                imageVector = Icons.Filled.Warning,
                iconColor = MaterialTheme.colorScheme.error,
                color = MaterialTheme.colorScheme.errorContainer,
            )
        }
        icon != null -> {
            Badge(
                imageBitmap = icon,
                modifier = Modifier
                    .scale(1.3f)
                    .height(18.dp),
            )
        }
        source.isLocal() -> {
            Badge(
                imageVector = Icons.Outlined.Folder,
                color = MaterialTheme.colorScheme.tertiary,
                iconColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
        else -> {
            // Default source icon (if source doesn't have an icon)
            Badge(
                imageVector = Icons.Outlined.LocalLibrary,
                color = MaterialTheme.colorScheme.tertiary,
                iconColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}

@Composable
internal fun DownloadsBadge(count: Long) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnviewedBadge(count: Long) {
    if (count > 0) {
        Badge(text = "$count")
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
    showLanguageIcon: Boolean = false,
) {
    if (isLocal) {
        Badge(
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        if (showLanguageIcon) {
            val context = LocalContext.current
            val flagCode = rememberLanguageCodeToFlag(sourceLanguage)
            val flagResId = FlagKit.getResId(context, flagCode)
            if (flagResId != 0) {
                Badge(
                    painter = painterResource(id = flagResId),
                    color = Color.Transparent,
                    modifier = Modifier
                        .width(25.dp)
                        .height(18.dp),
                )
            } else {
                Badge(
                    text = sourceLanguage.uppercase(),
                    color = MaterialTheme.colorScheme.tertiary,
                    textColor = MaterialTheme.colorScheme.onTertiary,
                )
            }
        } else {
            Badge(
                text = sourceLanguage.uppercase(),
                color = MaterialTheme.colorScheme.tertiary,
                textColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}

@Composable
internal fun LanguageIcon(
    sourceLanguage: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val flagCode = rememberLanguageCodeToFlag(sourceLanguage)
    val flagResId = FlagKit.getResId(context, flagCode)
    if (flagResId != 0) {
        Image(
            painter = painterResource(flagResId),
            contentDescription = sourceLanguage,
            modifier = modifier.size(16.dp),
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

private fun rememberLanguageCodeToFlag(language: String): String {
    return when (language.lowercase()) {
        "en" -> "us"
        "ja" -> "jp"
        "zh" -> "cn"
        "ko" -> "kr"
        "pt-br" -> "br"
        "es-419" -> "mx"
        "es" -> "es"
        "fr" -> "fr"
        "de" -> "de"
        "it" -> "it"
        "ru" -> "ru"
        "vi" -> "vn"
        "hi" -> "in"
        "bn" -> "bd"
        "ar" -> "sa"
        "tr" -> "tr"
        "id" -> "id"
        "ms" -> "my"
        "th" -> "th"
        "pl" -> "pl"
        "all" -> "eu"
        else -> language
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnviewedBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
