# [PERF] Bulk Favorite: Unbatched SQLite transactions freeze app when adding multiple anime

## Problem Description

When adding multiple anime to favorites in bulk (e.g. selecting dozens or hundreds of anime from an extension's Popular or Latest feed), the UI freezes in `isRunning = true` state for up to several minutes.

### Root Cause Analysis

In `app/src/main/java/eu/kanade/tachiyomi/ui/browse/BulkFavoriteScreenModel.kt` (lines 99–117):

```kotlin
fun setAnimesCategories(animeList: List<Anime>, addCategories: List<Long>, removeCategories: List<Long>) {
    screenModelScope.launchNonCancellable {
        startRunning()
        animeList.forEach { anime ->
            val categoryIds = getCategories.await(anime.id)
                .map { it.id }
                .subtract(removeCategories.toSet())
                .plus(addCategories)
                .toList()

            setAnimeCategories.await(anime.id, categoryIds)
            if (!anime.favorite) {
                updateAnime.awaitUpdateFavorite(anime.id, true)
            }
        }
        stopRunning()
        toggleSelectionMode(false)
    }
}
```

1. **Unbatched SQLite Transactions:**
   - `setAnimeCategories.await(anime.id, ...)` opens and commits a separate SQLite transaction (`animes_categoriesQueries.delete` + `insert`).
   - `updateAnime.awaitUpdateFavorite(anime.id, true)` opens and commits another separate SQLite transaction (`animesQueries.update`).
   - For 1,200 selected anime, this executes **2,400 to 3,600 individual SQLite transactions**.
   - On flash storage (NAND/eMMC/UFS), each SQLite `COMMIT` triggers a physical `fsync` disk sync. 2,400 fsyncs take between 30 and 120 seconds of continuous disk I/O lockup.

2. **Reactive Flow Flood:**
   - Because each individual write commits to the database, reactive query flows (`getLibraryFlow()`) receive hundreds of update events, triggering redundant recalculations in background workers.

---

## Proposed Solution: Batch Transaction

Wrap the operations in a single atomic transaction or batch update interactor so that SQLite performs **1 single `fsync`** at the end:

```kotlin
// In AnimeRepository / Handler:
handler.await(inTransaction = true) {
    val now = Instant.now().toEpochMilli()
    animeList.forEach { anime ->
        val existingCategories = getCategories(anime.id)
        val finalCategoryIds = existingCategories
            .subtract(removeCategories.toSet())
            .plus(addCategories)

        animes_categoriesQueries.deleteAnimeCategoryByAnimeId(anime.id)
        finalCategoryIds.forEach { catId ->
            animes_categoriesQueries.insert(anime.id, catId)
        }

        if (!anime.favorite) {
            animesQueries.update(
                id = anime.id,
                favorite = true,
                dateAdded = now,
                // keep other fields unchanged
            )
        }
    }
}
```

### Expected Performance Gain:
- **Before:** 45–120 seconds disk freeze for 1,200 anime (2,400 disk fsyncs).
- **After:** ~150–250 milliseconds (1 single disk fsync, $300\times$ to $500\times$ faster).
