package eu.kanade.domain

import eu.kanade.domain.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.episode.interactor.SetSeenStatus
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.extension.interactor.GetExtensionLanguages
import eu.kanade.domain.extension.interactor.GetExtensionSources
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.GetLanguagesWithSources
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.interactor.ToggleLanguage
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.SyncEpisodeProgressWithTrack
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.tachiyomi.ui.player.utils.TrackSelect
import mihon.data.repository.ExtensionRepoRepositoryImpl
import mihon.domain.episode.interactor.FilterEpisodesForDownload
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepoCount
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.ToggleExtensionRepoVisibility
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import tachiyomi.data.anime.AnimeMergeRepositoryImpl
import tachiyomi.data.anime.AnimeRepositoryImpl
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.custombutton.CustomButtonRepositoryImpl
import tachiyomi.data.episode.EpisodeRepositoryImpl
import tachiyomi.data.history.ActivityLogRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.release.ReleaseServiceImpl
import tachiyomi.data.source.SourceRepositoryImpl
import tachiyomi.data.source.StubSourceRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.anime.interactor.FetchInterval
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodesAndSeasons
import tachiyomi.domain.anime.interactor.GetCustomAnimeInfo
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.interactor.GetFavorites
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.anime.interactor.CalculateUserAffinity
import tachiyomi.domain.anime.interactor.GetMergedReferencesById
import tachiyomi.domain.anime.interactor.GetSeasonsByAnimeId
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.interactor.ResetViewerFlags
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.interactor.SetAnimeSeasonFlags
import tachiyomi.domain.anime.repository.AnimeMergeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.custombuttons.interactor.CreateCustomButton
import tachiyomi.domain.custombuttons.interactor.DeleteCustomButton
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.interactor.ReorderCustomButton
import tachiyomi.domain.custombuttons.interactor.ToggleFavoriteCustomButton
import tachiyomi.domain.custombuttons.interactor.UpdateCustomButton
import tachiyomi.domain.custombuttons.repository.CustomButtonRepository
import tachiyomi.domain.episode.interactor.GetAvailableScanlators
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.GetEpisodeByUrlAndAnimeId
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.GetExcludedScanlators
import tachiyomi.domain.episode.interactor.GetMergedEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.episode.interactor.SetExcludedScanlators
import tachiyomi.domain.episode.interactor.ShouldUpdateDbEpisode
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.repository.EpisodeRepository
import tachiyomi.domain.history.interactor.GetActivityLog
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.GetTotalWatchDuration
import tachiyomi.domain.history.interactor.LogActivity
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.ActivityLogRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.service.ReleaseService
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.interactor.GetRelatedAnime
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryAnime
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerAnime
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.repository.UpdatesRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class DomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<CategoryRepository> { CategoryRepositoryImpl(get()) }
        addFactory { GetCategories(get()) }
        addFactory { ResetCategoryFlags(get(), get()) }
        addFactory { SetDisplayMode(get()) }
        addFactory { SetSortModeForCategory(get(), get()) }
        addFactory { CreateCategoryWithName(get(), get()) }
        addFactory { RenameCategory(get()) }
        addFactory { ReorderCategory(get()) }
        addFactory { UpdateCategory(get()) }
        addFactory { DeleteCategory(get()) }

        // KMK -->
        addFactory { HideCategory(get()) }
        // KMK <--

        addSingletonFactory<AnimeRepository> { AnimeRepositoryImpl(get()) }
        addSingletonFactory<AnimeMergeRepository> { AnimeMergeRepositoryImpl(get()) }
        addFactory { GetDuplicateLibraryAnime(get()) }
        addFactory { GetFavorites(get()) }
        addFactory { GetLibraryAnime(get()) }
        addFactory { CalculateUserAffinity(get(), get(), get()) }
        addFactory { GetAnimeWithEpisodes(get(), get(), get()) }
        addFactory { GetCustomAnimeInfo(get()) }
        addFactory { GetSeasonsByAnimeId(get(), get()) }
        addFactory { GetAnimeWithEpisodesAndSeasons(get(), get(), get(), get()) }
        addFactory { GetAnimeByUrlAndSourceId(get()) }
        addFactory { GetAnime(get()) }
        addFactory { GetNextEpisodes(get(), get(), get(), get()) }
        addFactory { ResetViewerFlags(get()) }
        addFactory { SetAnimeEpisodeFlags(get(), get()) }
        addFactory { SetAnimeSeasonFlags(get()) }
        addFactory { FetchInterval(get()) }
        addFactory { SetAnimeDefaultEpisodeFlags(get(), get(), get()) }
        addFactory { SetAnimeViewerFlags(get()) }
        addFactory { NetworkToLocalAnime(get()) }
        addFactory { UpdateAnime(get(), get()) }
        addFactory { SyncSeasonsWithSource(get(), get(), get(), get(), get()) }
        addFactory { SetAnimeCategories(get()) }
        addFactory { GetMergedReferencesById(get()) }
        addFactory { GetExcludedScanlators(get()) }
        addFactory { SetExcludedScanlators(get()) }

        addSingletonFactory<ReleaseService> { ReleaseServiceImpl(get(), get()) }
        addFactory { GetApplicationRelease(get(), get()) }

        addSingletonFactory<TrackRepository> { TrackRepositoryImpl(get()) }
        addFactory { TrackEpisode(get(), get(), get(), get(), get(), get()) }
        addFactory { AddTracks(get(), get(), get(), get()) }
        addFactory { RefreshTracks(get(), get(), get(), get()) }
        addFactory { DeleteTrack(get()) }
        addFactory { GetTracksPerAnime(get()) }
        addFactory { GetTracks(get()) }
        addFactory { InsertTrack(get()) }
        addFactory { SyncEpisodeProgressWithTrack(get(), get(), get(), get()) }

        addSingletonFactory<EpisodeRepository> { EpisodeRepositoryImpl(get()) }
        addFactory { GetEpisode(get()) }
        addFactory { GetEpisodesByAnimeId(get()) }
        addFactory { GetMergedEpisodesByAnimeId(get(), get()) }
        addFactory { GetEpisodeByUrlAndAnimeId(get()) }
        addFactory { UpdateEpisode(get()) }
        addFactory { SetSeenStatus(get(), get(), get(), get(), get()) }
        addFactory { ShouldUpdateDbEpisode() }
        addFactory { tachiyomi.domain.season.interactor.GetAnimeSeasonsById(get()) }
        addFactory { tachiyomi.domain.season.interactor.ShouldUpdateDbSeason() }
        addFactory { tachiyomi.domain.season.interactor.SetAnimeDefaultSeasonFlags(get(), get(), get()) }
        addFactory { SyncEpisodesWithSource(get(), get(), get(), get(), get(), get(), get()) }
        addFactory { GetAvailableScanlators(get()) }
        addFactory { FilterEpisodesForDownload(get(), get(), get(), get()) }

        addSingletonFactory<HistoryRepository> { HistoryRepositoryImpl(get()) }
        addFactory { GetHistory(get()) }
        addFactory { UpsertHistory(get()) }
        addFactory { RemoveHistory(get()) }
        addFactory { GetTotalWatchDuration(get()) }

        addSingletonFactory<ActivityLogRepository> { ActivityLogRepositoryImpl(get()) }
        addFactory { LogActivity(get()) }
        addFactory { GetActivityLog(get()) }

        addFactory { DeleteDownload(get(), get()) }

        addFactory { GetExtensionsByType(get(), get(), get()) }
        addFactory { GetExtensionSources(get()) }
        addFactory { GetExtensionLanguages(get(), get()) }

        addSingletonFactory<UpdatesRepository> { UpdatesRepositoryImpl(get()) }
        addFactory { GetUpdates(get()) }

        addSingletonFactory<SourceRepository> { SourceRepositoryImpl(get(), get()) }
        addSingletonFactory<StubSourceRepository> { StubSourceRepositoryImpl(get()) }
        addFactory { GetEnabledSources(get(), get()) }
        addFactory { GetLanguagesWithSources(get(), get()) }
        addFactory { GetRemoteAnime(get()) }
        addFactory { GetRelatedAnime(get()) }
        addFactory { GetSourcesWithFavoriteCount(get(), get()) }
        addFactory { GetSourcesWithNonLibraryAnime(get()) }
        addFactory { SetMigrateSorting(get()) }
        addFactory { ToggleLanguage(get()) }
        addFactory { ToggleSource(get()) }
        addFactory { ToggleSourcePin(get()) }
        addFactory { TrustExtension(get(), get()) }

        addSingletonFactory<ExtensionRepoRepository> { ExtensionRepoRepositoryImpl(get()) }
        addFactory { ExtensionRepoService(get(), get()) }
        addFactory { GetExtensionRepo(get()) }
        addFactory { GetExtensionRepoCount(get()) }
        addFactory { CreateExtensionRepo(get(), get()) }
        addFactory { DeleteExtensionRepo(get()) }
        addFactory { ReplaceExtensionRepo(get()) }
        addFactory { UpdateExtensionRepo(get(), get()) }
        addFactory { ToggleExtensionRepoVisibility(get()) }

        addSingletonFactory<CustomButtonRepository> { CustomButtonRepositoryImpl(get()) }
        addFactory { CreateCustomButton(get()) }
        addFactory { DeleteCustomButton(get()) }
        addFactory { GetCustomButtons(get()) }
        addFactory { UpdateCustomButton(get()) }
        addFactory { ReorderCustomButton(get()) }
        addFactory { ToggleFavoriteCustomButton(get()) }

        addFactory { TrackSelect(get(), get()) }
    }
}
