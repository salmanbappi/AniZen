# AniZen ⇄ anikku provenance report

Generated: 2026-09-13 03:18

| | |
|---|---|
| AniZen HEAD | `f8cd9888bc91` (`preview`) |
| Upstream ref | `077dde5ff34b` |
| Merge base | `aa016c4c3244` |

AniZen's history is squashed, so this report derives *ours vs theirs* from the
recorded content ancestor instead of git ancestry.

## Summary

| Metric | Count |
|---|---:|
| Files in AniZen | 2262 |
| Files upstream | 2340 |
| Identical content | 814 |
| Differing content | 938 |
| AniZen-only files | 510 |
| Upstream-only files | 588 |
| Changed by AniZen since base | 1070 |
| Changed upstream since base | 1609 |
| **Overlapping paths (conflict surface)** | **619** |

## Conflict surface (upper bound)

| Shape | Count |
|---|---:|
| content vs content | 527 |
| modify / delete | 92 |
| deleted on both sides (no conflict) | 10 |

**Verdict: 619 paths need attention.** This is an upper bound:
a base-corrected 3-way merge auto-resolves paths whose edits do not overlap.

## Paths needing attention (619)

| Path | Shape |
|---|---|
| `.github/ISSUE_TEMPLATE/2_report_issue.yml` | content vs content |
| `CHANGELOG.md` | content vs content |
| `CONTRIBUTING.md` | content vs content |
| `app/build.gradle.kts` | content vs content |
| `app/proguard-rules.pro` | content vs content |
| `app/src/main/AndroidManifest.xml` | content vs content |
| `app/src/main/assets/aniyomi.lua` | content vs content |
| `app/src/main/baseline-prof.txt` | content vs content |
| `app/src/main/java/eu/kanade/domain/DomainModule.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/SYDomainModule.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/anime/interactor/SyncSeasonsWithSource.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/episode/model/Episode.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionsByType.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/source/interactor/GetEnabledSources.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/source/interactor/GetSourcesWithFavoriteCount.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/source/service/SourcePreferences.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/sync/SyncPreferences.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/track/interactor/AddTracks.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/track/service/TrackPreferences.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt` | content vs content |
| `app/src/main/java/eu/kanade/domain/ui/model/StartScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/anime/SeasonSettingsDialog.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/anime/components/AnimeSeasonListItem.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/ExtensionDetailsScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/ExtensionFilterScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/MigrateSearchScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/MigrateSourceScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/SourcesFilterScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/SourcesScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BaseBrowseItem.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BaseSourceItem.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceToolbar.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/BulkFavoriteDialogs.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchToolbar.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/category/CategoryScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/category/components/CategoryListItem.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/components/AppBar.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/components/Banners.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/components/BulkSelectionToolbar.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/components/SourceSearchBox.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/components/TabbedScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/history/HistoryScreenModelStateProvider.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/history/components/HistoryItem.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/history/components/HistoryWithRelationsProvider.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryBadges.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryContent.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt` | content vs content |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryPager.kt` | content vs content |
| _... 559 more_ | |

## Upstream-only files (588)

| Path | Shape |
|---|---|
| `.gemini/config.yaml` | changed |
| `.github/FUNDING.yml` | changed |
| `.github/scripts/release_note_generate.sh` | changed |
| `.github/workflows/build_benchmark.yml` | changed |
| `.github/workflows/build_dispatch_preview.yml` | changed |
| `.github/workflows/build_preview.yml` | changed |
| `.github/workflows/build_pull_request.yml` | changed |
| `.github/workflows/build_push.yml` | changed |
| `.github/workflows/build_release.yml` | changed |
| `.github/workflows/codeberg_mirror.yml` | changed |
| `.github/workflows/delete_merged_branch.yml` | changed |
| `.github/workflows/pr_label.yml` | changed |
| `.github/workflows/update_website.yml` | changed |
| `.idea/icon.png` | changed |
| `AGENTS.md` | changed |
| `CLAUDE.md` | changed |
| `app/google-services.json` | changed |
| `app/src/beta/res/values/colors.xml` | changed |
| `app/src/debug/AndroidManifest.xml` | changed |
| `app/src/debug/res/values/colors.xml` | changed |
| `app/src/main/aidl/mihon/app/shizuku/IShellInterface.aidl` | changed |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/GetAvailableScanlators.kt` | changed |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/SetReadStatus.kt` | changed |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt` | changed |
| `app/src/main/java/eu/kanade/domain/chapter/model/Chapter.kt` | changed |
| `app/src/main/java/eu/kanade/domain/chapter/model/ChapterFilter.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/CreateSortTag.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/DeleteSortTag.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/GetExcludedScanlators.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/GetSortTag.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/ReorderSortTag.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/SetExcludedScanlators.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/SmartSearchMerge.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/interactor/UpdateManga.kt` | changed |
| `app/src/main/java/eu/kanade/domain/manga/model/Manga.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/CreateSourceCategory.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/DeleteSourceCategory.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/GetExhSavedSearch.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/GetIncognitoState.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/GetShowLatest.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/GetSourceCategories.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/RenameSourceCategory.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/SetSourceCategories.kt` | changed |
| `app/src/main/java/eu/kanade/domain/source/interactor/ToggleIncognito.kt` | changed |
| `app/src/main/java/eu/kanade/domain/track/interactor/SyncChapterProgressWithTrack.kt` | changed |
| `app/src/main/java/eu/kanade/domain/track/interactor/TrackChapter.kt` | changed |
| `app/src/main/java/eu/kanade/domain/ui/model/AppIcon.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/BrowseTabWrapper.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/FeedOrderScreen.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/FeedScreen.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/RelatedMangasScreen.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/SourceFeedOrderScreen.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/SourceFeedScreen.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceSimpleToolbar.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/components/FeedOrderListItem.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/components/RelatedMangasComfortableGrid.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/components/RelatedMangasCompactGrid.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/components/RelatedMangasList.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/browse/components/SourceFeedDialogs.kt` | changed |
| `app/src/main/java/eu/kanade/presentation/category/BiometricTimesScreen.kt` | changed |
| _... 528 more_ | |

## AniZen-only files (510)

| Path | Shape |
|---|---|
| `'` | changed |
| `.github/release.yml` | changed |
| `.github/wiki/Anime4K-Guide.md` | changed |
| `.github/wiki/Architecture.md` | changed |
| `.github/wiki/Backups.md` | changed |
| `.github/wiki/Categories.md` | changed |
| `.github/wiki/Changelog.md` | changed |
| `.github/wiki/Contributing.md` | changed |
| `.github/wiki/FAQ-Browse.md` | changed |
| `.github/wiki/FAQ-Downloads.md` | changed |
| `.github/wiki/FAQ-General.md` | changed |
| `.github/wiki/FAQ-Library.md` | changed |
| `.github/wiki/FAQ-Player.md` | changed |
| `.github/wiki/FAQ-Storage.md` | changed |
| `.github/wiki/FAQ.md` | changed |
| `.github/wiki/Features.md` | changed |
| `.github/wiki/Home.md` | changed |
| `.github/wiki/Installation.md` | changed |
| `.github/wiki/Local-Anime-Source.md` | changed |
| `.github/wiki/Player-Guide.md` | changed |
| `.github/wiki/Shizuku.md` | changed |
| `.github/wiki/Source-Migration.md` | changed |
| `.github/wiki/Style-Guide.md` | changed |
| `.github/wiki/Tracking.md` | changed |
| `.github/wiki/Troubleshooting.md` | changed |
| `.github/wiki/_Sidebar.md` | changed |
| `.github/workflows/beta.yml` | changed |
| `.github/workflows/preview.yml` | changed |
| `.github/workflows/release.yml` | changed |
| `.github/workflows/wiki.yml` | changed |
| `.tmp/new_strings.xml` | changed |
| `Build & Preview Artifacts/10_Build Preview APK.txt` | changed |
| `anikku-tracker/SKILL.md` | changed |
| `app/anizen.jks` | changed |
| `app/src/beta/res/values/strings.xml` | changed |
| `app/src/beta/res/xml/shortcuts.xml` | changed |
| `app/src/dev/java/mihon/core/firebase/FirebaseConfig.kt` | changed |
| `app/src/main/aidl/eu/kanade/tachiyomi/shizuku/IShellInterface.aidl` | changed |
| `app/src/main/assets/client_secrets.json` | changed |
| `app/src/main/assets/shaders/Anime4K_AutoDownscalePre_x2.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Clamp_Highlights.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Restore_CNN_L.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Restore_CNN_M.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Restore_CNN_S.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_L.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_M.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_S.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_L.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_M.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_L.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_M.glsl` | changed |
| `app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_S.glsl` | changed |
| `app/src/main/java/eu/kanade/domain/ai/AiPreferences.kt` | changed |
| `app/src/main/java/eu/kanade/domain/anime/interactor/UpdateAnime.kt` | changed |
| `app/src/main/java/eu/kanade/domain/anime/model/Anime.kt` | changed |
| `app/src/main/java/eu/kanade/domain/episode/interactor/SetSeenStatus.kt` | changed |
| `app/src/main/java/eu/kanade/domain/episode/interactor/SyncEpisodesWithSource.kt` | changed |
| `app/src/main/java/eu/kanade/domain/episode/model/EpisodeFilter.kt` | changed |
| `app/src/main/java/eu/kanade/domain/track/interactor/SyncEpisodeProgressWithTrack.kt` | changed |
| _... 450 more_ | |

## Resolving

1. Keep every `// ANZ` block; take upstream outside the markers.
2. Preserve inherited blocks (`// ANK`, `// KMK`, `// SY`, `// AY`) unless the
   review says otherwise — they map to other upstreams.
3. Re-run `scripts/check-provenance.sh` before pushing.
4. Merge on a sync branch, never directly into `preview`.

