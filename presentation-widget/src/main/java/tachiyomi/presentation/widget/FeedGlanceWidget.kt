package tachiyomi.presentation.widget

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import coil3.annotation.ExperimentalCoilApi
import coil3.asDrawable
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.transform.RoundedCornersTransformation
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.presentation.widget.R
import tachiyomi.presentation.widget.components.CoverHeight
import tachiyomi.presentation.widget.components.CoverWidth
import tachiyomi.presentation.widget.components.LockedWidget
import tachiyomi.presentation.widget.components.UpdatesWidget
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius
import tachiyomi.presentation.widget.util.calculateRowAndColumnCount
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime

class FeedGlanceWidget(
    private val context: Context = Injekt.get<Application>(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val securityPreferences: SecurityPreferences = Injekt.get(),
) : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    val foreground = ColorProvider(R.color.appwidget_on_secondary_container)
    val background = ImageProvider(R.drawable.appwidget_background)
    val topPadding = 16.dp
    val bottomPadding = 16.dp

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val locked = securityPreferences.useAuthenticator().get()
        val containerModifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .appWidgetBackground()
            .padding(top = topPadding, bottom = bottomPadding)
            .appWidgetBackgroundRadius()

        val manager = GlanceAppWidgetManager(context)
        val sizes = try {
            manager.getAppWidgetSizes(id)
        } catch (e: Exception) {
            emptyList()
        }
        val (rowCount, columnCount) = sizes
            .maxByOrNull { it.height.value * it.width.value }
            ?.calculateRowAndColumnCount(topPadding, bottomPadding)
            ?: Pair(2, 4)

        provideContent {
            if (locked) {
                LockedWidget(foreground = foreground, modifier = containerModifier)
                return@provideContent
            }

            val flow = remember {
                getUpdates.subscribe(ZonedDateTime.now().minusMonths(3).toInstant())
                    .map { updates ->
                        val animeList = updates
                            .distinctBy { it.animeId }
                            .map {
                                Anime.create().copy(
                                    id = it.animeId,
                                    source = it.sourceId,
                                    favorite = true,
                                    coverLastModified = it.coverData.lastModified,
                                    ogTitle = it.animeTitle,
                                    ogThumbnailUrl = it.coverData.url,
                                )
                            }
                        prepareAnimeData(animeList, rowCount, columnCount)
                    }
            }

            val data by flow.collectAsState(initial = null as ImmutableList<Pair<Long, Bitmap?>>?)
            UpdatesWidget(
                data = data,
                contentColor = foreground,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                modifier = containerModifier,
            )
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun prepareAnimeData(
        animeList: List<Anime>,
        rowCount: Int,
        columnCount: Int,
    ): ImmutableList<Pair<Long, Bitmap?>> {
        val widthPx = CoverWidth.value.toInt().dpToPx
        val heightPx = CoverHeight.value.toInt().dpToPx
        val roundPx = context.resources.getDimension(R.dimen.appwidget_inner_radius)
        return withIOContext {
            animeList
                .take(rowCount * columnCount)
                .map { anime ->
                    val request = ImageRequest.Builder(context)
                        .data(
                            AnimeCover(
                                animeId = anime.id,
                                sourceId = anime.source,
                                isAnimeFavorite = anime.favorite,
                                ogUrl = anime.thumbnailUrl,
                                lastModified = anime.coverLastModified,
                            ),
                        )
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .precision(Precision.EXACT)
                        .size(widthPx, heightPx)
                        .scale(Scale.FILL)
                        .let {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                it.transformations(RoundedCornersTransformation(roundPx))
                            } else {
                                it
                            }
                        }
                        .build()
                    val bitmap = context.imageLoader.executeBlocking(request)
                        .image
                        ?.asDrawable(context.resources)
                        ?.toBitmap()
                    Pair(anime.id, bitmap)
                }
                .toImmutableList()
        }
    }
}
