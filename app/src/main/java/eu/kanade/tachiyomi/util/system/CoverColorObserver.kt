package eu.kanade.tachiyomi.util.system

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of per-cover vibrant colors and aspect ratios, now version-aware and
 * persistent.
 *
 * Every entry carries the cover's [lastModified] so a changed cover invalidates stale
 * entries (they are re-extracted on next sight) instead of showing a wrong cached color.
 *
 * Writes are debounced and batched into the library StringSet prefs
 * ([LibraryPreferences.coverColors]/[LibraryPreferences.coverRatios]) so a fast fling
 * through the library never hits SharedPreferences. [load] hydrates both maps at startup
 * so the *first* frame of a scroll is already warm.
 *
 * Format of a persisted entry: "animeId:lastModified:value".
 */
object CoverColorObserver {
    private val _vibrantColors = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val vibrantColors = _vibrantColors.asStateFlow()

    private val _ratios = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val ratios = _ratios.asStateFlow()

    /** Version of the cover each cached color/ratio was extracted from. */
    private val lastModified = ConcurrentHashMap<Long, Long>()

    /** Pending entries not yet flushed to prefs (id -> "id:version:value"). */
    private val pendingColors = mutableSetOf<String>()
    private val pendingRatios = mutableSetOf<String>()

    private val lock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    private val prefs: LibraryPreferences
        get() = Injekt.get<LibraryPreferences>()

    // region Update

    /**
     * Records a freshly extracted vibrant color.
     *
     * @return true when a new value was actually stored (color or version changed).
     */
    fun update(animeId: Long, color: Int, coverLastModified: Long = 0L): Boolean {
        var changed = false
        synchronized(lock) {
            val previous = _vibrantColors.value[animeId]
            val previousVersion = lastModified[animeId]
            if (previous != color || previousVersion != coverLastModified) {
                _vibrantColors.update { it + (animeId to color) }
                lastModified[animeId] = coverLastModified
                pendingColors += encode(animeId, coverLastModified, color)
                changed = true
            }
        }
        if (changed) scheduleFlush()
        return changed
    }

    /**
     * Records a freshly measured cover ratio.
     *
     * @return true when a new value was actually stored (ratio or version changed).
     */
    fun updateRatio(animeId: Long, ratio: Float, coverLastModified: Long = 0L): Boolean {
        var changed = false
        synchronized(lock) {
            val previous = _ratios.value[animeId]
            val previousVersion = lastModified[animeId]
            val sameValue = previous != null && Math.abs(previous - ratio) < 0.01f
            if (!sameValue || previousVersion != coverLastModified) {
                _ratios.update { it + (animeId to ratio) }
                lastModified[animeId] = coverLastModified
                pendingRatios += encode(animeId, coverLastModified, ratio)
                changed = true
            }
        }
        if (changed) scheduleFlush()
        return changed
    }

    // endregion

    // region Read

    /**
     * Cached vibrant color for [animeId].
     *
     * When [coverLastModified] is provided and doesn't match the stored version, null is
     * returned so the caller knows the cache is stale and must re-extract.
     */
    fun get(animeId: Long, coverLastModified: Long = -1L): Int? {
        if (coverLastModified >= 0L) {
            val version = lastModified[animeId]
            if (version != null && version != coverLastModified) return null
        }
        return _vibrantColors.value[animeId]
    }

    /**
     * Cached ratio for [animeId], version-aware like [get].
     */
    fun getRatio(animeId: Long, coverLastModified: Long = -1L): Float? {
        if (coverLastModified >= 0L) {
            val version = lastModified[animeId]
            if (version != null && version != coverLastModified) return null
        }
        return _ratios.value[animeId]
    }

    // endregion

    // region Persistence

    /**
     * Hydrates both maps from prefs and prunes entries that are no longer favorites or
     * whose version is unknown. Safe to call repeatedly (e.g. at startup).
     */
    suspend fun load() {
        // CancellationException must propagate, not be swallowed by runCatching.
        val favorites = try {
            Injekt.get<AnimeRepository>().getFavorites().map { it.id }.toSet()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

        val colorsPref = prefs.coverColors()
        val ratiosPref = prefs.coverRatios()

        // Only keep entries whose anime is (still) a favorite; unknown versions are dropped
        // (they will be re-extracted on sight) but never re-extracted for the library.
        val favoriteIdFilter: (Long) -> Boolean = { id -> favorites.isEmpty() || id in favorites }
        val colors = HashMap<Long, Int>()
        val ratios = HashMap<Long, Float>()
        val versions = HashMap<Long, Long>()
        var pruned = false

        for (entry in colorsPref.get()) {
            val parsed = parseColor(entry)
            if (parsed == null || !favoriteIdFilter(parsed.first)) {
                pruned = true
                continue
            }
            colors[parsed.first] = parsed.second
            versions[parsed.first] = parsed.third
        }
        for (entry in ratiosPref.get()) {
            val parsed = parseRatio(entry)
            if (parsed == null || !favoriteIdFilter(parsed.first)) {
                pruned = true
                continue
            }
            ratios[parsed.first] = parsed.second
            versions.putIfAbsent(parsed.first, parsed.third)
        }

        synchronized(lock) {
            // Merge (not replace): a cover extracted while this load was running must not
            // be wiped by the snapshot read from prefs moments earlier. In-memory entries
            // (freshest) win over the prefs snapshot; prefs fill in ids not yet seen.
            if (colors.isNotEmpty()) _vibrantColors.value = colors + _vibrantColors.value
            if (ratios.isNotEmpty()) _ratios.value = ratios + _ratios.value
            // Same rule for versions: keep the in-memory (newer) version when present.
            versions.forEach { (id, v) -> lastModified.putIfAbsent(id, v) }

            // Persist only when the persisted rows actually changed: something was pruned
            // or a pending (favorite) entry must be written. Skipping the write otherwise
            // avoids a wholesale prefs rewrite + listeners on every app start.
            val pendingFavoriteColor = pendingColors.any { entry ->
                parseColor(entry)?.let(favoriteIdFilter) == true
            }
            val pendingFavoriteRatio = pendingRatios.any { entry ->
                parseRatio(entry)?.let(favoriteIdFilter) == true
            }
            if (pruned || pendingFavoriteColor || pendingFavoriteRatio) {
                val persistedColors = colors.keys.mapTo(mutableSetOf()) { id ->
                    encode(id, versions[id] ?: 0L, colors[id] ?: 0)
                }
                val persistedRatios = ratios.keys.mapTo(mutableSetOf()) { id ->
                    encode(id, versions[id] ?: 0L, ratios[id] ?: 0f)
                }
                // Drop the stale tuple for any id that has a pending replacement, then apply
                // the pending entries (same favorites filter as [flushInternal]: browsing-only
                // colors are not persisted — they re-extract cheaply on next sight).
                val pendingColorIds = pendingColors.mapNotNullTo(mutableSetOf()) { parseColor(it)?.first }
                val pendingRatioIds = pendingRatios.mapNotNullTo(mutableSetOf()) { parseRatio(it)?.first }
                persistedColors.removeAll { entry -> parseColor(entry)?.first in pendingColorIds }
                persistedRatios.removeAll { entry -> parseRatio(entry)?.first in pendingRatioIds }
                for (entry in pendingColors) {
                    val parsed = parseColor(entry)
                    if (parsed != null && favoriteIdFilter(parsed.first)) persistedColors += entry
                }
                for (entry in pendingRatios) {
                    val parsed = parseRatio(entry)
                    if (parsed != null && favoriteIdFilter(parsed.first)) persistedRatios += entry
                }
                colorsPref.set(persistedColors)
                ratiosPref.set(persistedRatios)
                pendingColors.clear()
                pendingRatios.clear()
            }
        }

        Timber.tag(TAG).d("Hydrated %d colors, %d ratios from prefs", colors.size, ratios.size)
    }

    /** Immediately persists the current maps, pruning non-favorites along the way. */
    fun flush() {
        synchronized(lock) {
            if (flushJob?.isActive == true) {
                flushJob?.cancel()
            }
            flushJob = scope.launch {
                flushInternal()
            }
        }
    }

    // endregion

    // region Internal

    private fun scheduleFlush() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                flushInternal()
            }
        }
    }

    private suspend fun flushInternal() {
        // Cheap lock-free-ish exit when nothing is pending.
        val hasPending = synchronized(lock) { pendingColors.isNotEmpty() || pendingRatios.isNotEmpty() }
        if (!hasPending) return

        // Suspend DB query must run OUTSIDE the lock: holding the monitor across an await
        // would stall concurrent update()/updateRatio() callers (extraction threads) for the
        // whole query duration. CancellationException must NOT be swallowed (runCatching
        // catches it), or a cancelled flush would keep writing after App.onStop replaced it.
        val favorites = try {
            Injekt.get<AnimeRepository>().getFavorites().map { it.id }.toSet()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        val favoriteIdFilter: (Long) -> Boolean = { id -> favorites.isEmpty() || id in favorites }

        synchronized(lock) {
            if (pendingColors.isEmpty() && pendingRatios.isEmpty()) return

            val colorsPref = prefs.coverColors()
            val ratiosPref = prefs.coverRatios()
            val colors = colorsPref.get().toMutableSet()
            val ratios = ratiosPref.get().toMutableSet()

            // Drop stale tuples: prune non-favorites and remove any tuple whose animeId
            // has a pending replacement (the fresh pending entry is added below).
            val pendingColorIds = pendingColors.mapNotNullTo(mutableSetOf()) { parseColor(it)?.first }
            val pendingRatioIds = pendingRatios.mapNotNullTo(mutableSetOf()) { parseRatio(it)?.first }
            colors.removeAll { entry ->
                val parsed = parseColor(entry) ?: return@removeAll true
                !favoriteIdFilter(parsed.first) || parsed.first in pendingColorIds
            }
            ratios.removeAll { entry ->
                val parsed = parseRatio(entry) ?: return@removeAll true
                !favoriteIdFilter(parsed.first) || parsed.first in pendingRatioIds
            }

            // Apply pending (ids already pruned above are filtered again defensively).
            for (entry in pendingColors) {
                val parsed = parseColor(entry) ?: continue
                if (!favoriteIdFilter(parsed.first)) continue
                colors += entry
            }
            for (entry in pendingRatios) {
                val parsed = parseRatio(entry) ?: continue
                if (!favoriteIdFilter(parsed.first)) continue
                ratios += entry
            }

            // Always write back: keeps prefs pruned when the whole library shrank.
            colorsPref.set(colors)
            ratiosPref.set(ratios)

            pendingColors.clear()
            pendingRatios.clear()

            Timber.tag(TAG).d("Flushed %d colors, %d ratios", colors.size, ratios.size)
        }
    }

    // endregion

    // region Encoding

    private fun encode(animeId: Long, coverLastModified: Long, value: Any): String {
        return "$animeId:$coverLastModified:$value"
    }

    /** Returns (animeId, color, lastModified) or null when malformed. */
    private fun parseColor(entry: String): Triple<Long, Int, Long>? {
        val parts = entry.split(':', limit = 3)
        if (parts.size != 3) return null
        val id = parts[0].toLongOrNull() ?: return null
        val version = parts[1].toLongOrNull() ?: return null
        val color = parts[2].toIntOrNull() ?: return null
        return Triple(id, color, version)
    }

    /** Returns (animeId, ratio, lastModified) or null when malformed. */
    private fun parseRatio(entry: String): Triple<Long, Float, Long>? {
        val parts = entry.split(':', limit = 3)
        if (parts.size != 3) return null
        val id = parts[0].toLongOrNull() ?: return null
        val version = parts[1].toLongOrNull() ?: return null
        val ratio = parts[2].toFloatOrNull() ?: return null
        return Triple(id, ratio, version)
    }

    // endregion

    private const val FLUSH_DEBOUNCE_MS = 500L
    private const val TAG = "CoverColorObserver"
}
