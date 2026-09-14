package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.animesource.model.Video
import java.util.Locale

object DefaultStreamSelector {
    private const val VERSION_V2 = "v2"
    private const val VERSION_V3 = "v3"
    private const val VERSION_V4 = "v4"
    private const val FIELD_SEP = '\u001D'
    private const val HOSTER_SEP = '\u001C'
    private const val HOSTER_KV_SEP = '\u001B'

    private val EPISODE_NUMBER_PATTERN = Regex(
        """(?i)(?:^|[\s/\-_\[])(?:ep(?:isode)?|e|#)?\s*0*(\d{1,4})(?:\s*v\d+)?(?:$|[\s\]\-_.])""",
    )
    private val SIZE_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*(gb|mb|kb)\b""", RegexOption.IGNORE_CASE)
    private val SEEDER_PATTERN = Regex("""\b(\d{1,6})\s*(?:seeders?|seeds?)\b""", RegexOption.IGNORE_CASE)
    private val SEEDER_EMOJI_PATTERN = Regex("""(?:👤|🌱)\s*(\d{1,6})""")
    private val RESOLUTION_PATTERN = Regex("""(?i)\b(2160p|1080p|720p|480p|4k)\b""")
    private val JUNK_PATH_PATTERN = Regex(
        """(?i)(?:^|[\s/\\])(bonus|specials?|extras?|previews?|web\s*preview|trailer|nc|op\s*\d|ed\s*\d)(?:[\s/\\]|$)""",
    )
    private val BRACKET_GROUP_PATTERN = Regex("""\[(.{1,48}?)]""")

    private val CODEC_TOKEN_PATTERN = Regex("""\b(hevc|x265|h265|265|x264|h264|264|av1|vp9)\b""")
    private val DUAL_AUDIO_PATTERN = Regex("""\bdual[ ._-]*audio\b""")
    private val DUBBED_PATTERN = Regex("""\bdub(?:bed)?\b""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val NUMBER_TAG_PATTERN = Regex("""\b0*(\d{1,4})\s*(?:v\d+)?\b""")
    private val BRACKETS_PARENS_PATTERN = Regex("""[\[\]()]""")
    private val META_BRACKET_PATTERN = Regex("""download|cached|torrentio|seadex|tb\+|best|dual\s*audio""")

    private val KNOWN_PROVIDERS = listOf(
        "1337x", "torrentgalaxy", "nyaasi", "seadex", "anidex", "tokyotosho",
        "horriblesubs", "magnetdl", "thepiratebay", "kickasstorrents", "eztv", "rarbg", "yts",
        "ilcorsaronero",
    )
    private val META_TAGS_SET = setOf("seadex", "tb+", "tb")

    fun selectorFor(video: Video, hosterName: String = ""): String = encode(fingerprintFor(video, hosterName))

    fun isDefaultMatch(selector: String, video: Video, candidates: List<Video>): Boolean {
        if (selector.isBlank() || candidates.isEmpty()) return false
        val bestIndex = findBestMatchIndex(selector, candidates)
        if (bestIndex < 0) return false
        return videosEqual(candidates[bestIndex], video)
    }

    fun findBestMatch(selector: String, candidates: List<Video>): Video? {
        val index = findBestMatchIndex(selector, candidates)
        return index.takeIf { it >= 0 }?.let { candidates[it] }
    }

    /**
     * Index of the best matching video for UI highlight / scroll.
     * Tries strict continuity match first, then relaxed (release group + provider + resolution).
     */
    fun findBestMatchIndex(selector: String, candidates: List<Video>, hosterName: String = ""): Int {
        if (selector.isBlank() || candidates.isEmpty()) return -1
        val hosterSelector = getSelectorForHoster(selector, hosterName)
        if (hosterSelector.isBlank()) return -1
        return findBestMatchIndexInternal(hosterSelector, candidates, hosterName, relaxed = false)
            .takeIf { it >= 0 }
            ?: findBestMatchIndexInternal(hosterSelector, candidates, hosterName, relaxed = true)
    }

    private fun findBestMatchIndexInternal(
        selector: String,
        candidates: List<Video>,
        hosterName: String,
        relaxed: Boolean,
    ): Int {
        val saved = decode(selector)
        if (saved == null) {
            val best = findBestLegacyTokenMatch(selector, candidates) ?: return -1
            val byIdentity = candidates.indexOf(best)
            if (byIdentity >= 0) return byIdentity
            return candidates.indexOfFirst { videosEqual(it, best) }
        }

        var bestIndex = -1
        var bestScore = 0
        candidates.forEachIndexed { index, video ->
            val score = scoreMatch(saved, video, hosterName, continuity = true, relaxed = relaxed)
            when {
                score > bestScore -> {
                    bestScore = score
                    bestIndex = index
                }
                score == bestScore &&
                    score > 0 &&
                    bestIndex >= 0 &&
                    sizeBytes(video) > sizeBytes(candidates[bestIndex]) -> {
                    bestIndex = index
                }
            }
        }
        return if (bestScore > 0) bestIndex else -1
    }

    fun findBestInHosters(
        selector: String,
        hosterStates: List<eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState>,
    ): Pair<Int, Int>? = findRankedInHosters(selector, hosterStates).firstOrNull()

    fun findRankedInHosters(
        selector: String,
        hosterStates: List<eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState>,
    ): List<Pair<Int, Int>> = findRankedInHostersInternal(selector, hosterStates, relaxed = false)

    /** Lower bar for cross-episode continuity (release group + provider + size band). */
    fun findRankedInHostersRelaxed(
        selector: String,
        hosterStates: List<eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState>,
    ): List<Pair<Int, Int>> = findRankedInHostersInternal(selector, hosterStates, relaxed = true)

    private fun findRankedInHostersInternal(
        selector: String,
        hosterStates: List<eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState>,
        relaxed: Boolean,
    ): List<Pair<Int, Int>> {
        if (selector.isBlank()) return emptyList()
        val ranked = mutableListOf<Triple<Int, Int, Int>>()
        hosterStates.forEachIndexed { hosterIdx, state ->
            if (state !is eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState.Ready) return@forEachIndexed
            val hosterSelector = getSelectorForHoster(selector, state.name)
            if (hosterSelector.isBlank()) return@forEachIndexed
            val saved = decode(hosterSelector)
            state.videoList.forEachIndexed { videoIdx, video ->
                val score = if (saved != null) {
                    scoreMatch(saved, video, state.name, continuity = true, relaxed = relaxed)
                } else {
                    val tokenScore = legacyTokenScore(hosterSelector, video)
                    if (tokenScore >= minOf(legacyTokens(hosterSelector).size, 3) * 8) tokenScore else 0
                }
                if (score > 0) {
                    ranked.add(Triple(hosterIdx, videoIdx, score))
                }
            }
        }
        return ranked
            .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.third }.thenByDescending { it.first })
            .map { it.first to it.second }
    }

    fun findVideoInHosters(
        hosterStates: List<eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState>,
        target: Video,
    ): Pair<Int, Int>? {
        hosterStates.forEachIndexed { hosterIdx, state ->
            if (state !is eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState.Ready) return@forEachIndexed
            val videoIdx = state.videoList.indexOfFirst { videosEqual(it, target) }
            if (videoIdx >= 0) return hosterIdx to videoIdx
        }
        return findBestInHosters(selectorFor(target), hosterStates)
    }

    fun matches(selector: String, video: Video): Boolean =
        isDefaultMatch(selector, video, listOf(video))

    fun summary(selector: String): String {
        val fp = decode(selector) ?: return selector.split('|').joinToString(", ")
        return buildList {
            if (fp.releaseGroup.isNotBlank()) add(fp.releaseGroup)
            if (fp.qualityTag.isNotBlank()) add(fp.qualityTag)
            if (fp.ripLabel.isNotBlank() && fp.ripLabel != fp.qualityTag) add(fp.ripLabel)
            if (fp.provider.isNotBlank()) add(fp.provider)
            if (fp.sizeBytes > 0) add(formatSize(fp.sizeBytes))
            if (fp.seeders >= 0) add("${fp.seeders} seeders")
        }.joinToString(", ").ifBlank { fp.exactNorm.take(48) }
    }

    private data class StreamFingerprint(
        val exactNorm: String,
        val batchNorm: String,
        val fileBaseNorm: String,
        val releaseGroup: String,
        val ripLabel: String,
        val qualityTag: String,
        val sizeBytes: Long,
        val seeders: Int,
        val provider: String,
        val hosterName: String = "",
    )

    private const val EXACT_MATCH_THRESHOLD = 120
    private const val CONTINUITY_MATCH_THRESHOLD = 72
    private const val CONTINUITY_RELAXED_THRESHOLD = 48

    private fun fingerprintFor(video: Video, hosterName: String = ""): StreamFingerprint {
        val lines = video.videoTitle.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val rawTitle = lines.joinToString(" ")
        
        val fileLine = lines.lastOrNull {
            ".mkv" in it.lowercase(Locale.ENGLISH) || ".mp4" in it.lowercase(Locale.ENGLISH)
        } ?: lines.lastOrNull { !it.startsWith("Provider:", ignoreCase = true) && !it.startsWith("Size:", ignoreCase = true) }
            ?: lines.lastOrNull()
            ?: rawTitle
        val headerLine = lines.firstOrNull { it.startsWith('[') && RESOLUTION_PATTERN.containsMatchIn(it) }
            ?: lines.firstOrNull { RESOLUTION_PATTERN.containsMatchIn(it) }
            ?: lines.firstOrNull { it.startsWith('[') }
            ?: lines.firstOrNull()
            ?: rawTitle
        val releaseGroup = parsePrimaryReleaseGroup(fileLine, headerLine)

        return StreamFingerprint(
            exactNorm = normalizeExact(rawTitle),
            batchNorm = normalizeBatch(rawTitle),
            fileBaseNorm = normalizeFileBase(fileLine),
            releaseGroup = releaseGroup,
            ripLabel = headerLine.lowercase(Locale.ENGLISH).trim(),
            qualityTag = parseQualityTag(rawTitle),
            sizeBytes = parseSizeBytes(video.videoTitle),
            seeders = parseSeeders(video.videoTitle),
            provider = parseProvider(video.videoTitle),
            hosterName = hosterName,
        )
    }

    private fun scoreMatch(
        saved: StreamFingerprint,
        video: Video,
        candidateHosterName: String = "",
        continuity: Boolean,
        relaxed: Boolean,
    ): Int {
        val candidate = fingerprintFor(video)
        var score = 0

        if (saved.hosterName.isNotBlank() && candidateHosterName.isNotBlank()) {
            if (saved.hosterName.equals(candidateHosterName, ignoreCase = true)) {
                score += 40
            } else {
                score -= 80
            }
        }

        if (saved.provider.isNotBlank() && saved.provider == candidate.provider) score += 14

        if (saved.qualityTag.isNotBlank() && saved.qualityTag == candidate.qualityTag) score += 16

        if (saved.releaseGroup.isNotBlank() && saved.releaseGroup == candidate.releaseGroup) score += 30

        val savedRip = saved.ripLabel.normalizeRip()
        val candidateRip = candidate.ripLabel.normalizeRip()
        if (savedRip.isNotBlank() && (candidateRip.contains(savedRip) || savedRip in candidate.batchNorm)) {
            score += 20
        }

        val batchSim = batchSimilarity(saved.batchNorm, candidate.batchNorm)
        when {
            batchSim >= 0.85 -> score += 34
            batchSim >= 0.55 -> score += 24
            batchSim >= 0.35 -> score += if (relaxed) 18 else 10
        }

        val fileSim = batchSimilarity(saved.fileBaseNorm, candidate.fileBaseNorm)
        when {
            fileSim >= 0.85 -> score += 28
            fileSim >= 0.55 -> score += 18
            fileSim >= 0.35 -> score += if (relaxed) 14 else 8
        }

        if (saved.exactNorm.isNotBlank() && candidate.exactNorm == saved.exactNorm) score += 80

        if (saved.sizeBytes > 0 && candidate.sizeBytes > 0) {
            val ratio = candidate.sizeBytes.toDouble() / saved.sizeBytes.toDouble()
            when {
                ratio in 0.75..1.35 -> score += 24
                ratio in 0.5..1.8 -> score += if (relaxed) 14 else 10
                saved.sizeBytes >= 200L * 1024 * 1024 && candidate.sizeBytes < 80L * 1024 * 1024 -> score -= 60
                saved.sizeBytes < 80L * 1024 * 1024 && candidate.sizeBytes >= 200L * 1024 * 1024 -> score -= 20
            }
        }

        if (saved.seeders >= 0 && candidate.seeders >= 0 && saved.seeders == candidate.seeders) score += 6

        if (relaxed &&
            saved.releaseGroup.isNotBlank() &&
            saved.releaseGroup == candidate.releaseGroup &&
            saved.provider.isNotBlank() &&
            saved.provider == candidate.provider &&
            (saved.qualityTag.isBlank() || saved.qualityTag == candidate.qualityTag)
        ) {
            score = maxOf(score, 52)
        }

        if (continuity) {
            if (saved.sizeBytes >= 200L * 1024 * 1024 && isJunkCandidate(candidate)) score -= 100
            if (saved.batchNorm.isNotBlank() && batchSim < 0.25) {
                score -= if (relaxed) 12 else 40
            }
        } else if (isJunkCandidate(candidate) && !isJunkCandidate(saved)) {
            score -= 40
        }

        val threshold = when {
            relaxed -> CONTINUITY_RELAXED_THRESHOLD
            continuity -> CONTINUITY_MATCH_THRESHOLD
            else -> EXACT_MATCH_THRESHOLD
        }
        return if (score >= threshold) score else 0
    }

    private fun batchSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.contains(b) || b.contains(a)) return 1.0
        val tokensA = a.split(' ').filter { it.length >= 3 }.toSet()
        val tokensB = b.split(' ').filter { it.length >= 3 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.count { it in tokensB }
        return intersection.toDouble() / minOf(tokensA.size, tokensB.size)
    }

    private fun isJunkCandidate(fp: StreamFingerprint): Boolean =
        JUNK_PATH_PATTERN.containsMatchIn(fp.exactNorm) ||
            JUNK_PATH_PATTERN.containsMatchIn(fp.fileBaseNorm) ||
            (fp.sizeBytes in 1..120L * 1024 * 1024 && "preview" in fp.exactNorm)

    private fun findBestLegacyTokenMatch(selector: String, candidates: List<Video>): Video? {
        val savedTokens = legacyTokens(selector)
        if (savedTokens.isEmpty()) return null
        return candidates
            .map { it to legacyTokenScore(selector, it) }
            .filter { it.second >= minOf(savedTokens.size, 3) * 8 }
            .maxWithOrNull(compareBy<Pair<Video, Int>> { it.second }.thenBy { sizeBytes(it.first) })
            ?.first
    }

    private fun legacyTokenScore(selector: String, video: Video): Int {
        val savedTokens = legacyTokens(selector)
        val videoTokens = legacyTokensFor(video).toSet()
        return savedTokens.count { it in videoTokens } * 10
    }

    private fun legacyTokens(selector: String): List<String> =
        selector.split('|').map { it.trim() }.filter { it.isNotBlank() }

    private fun legacyTokensFor(video: Video): List<String> {
        val title = video.videoTitle.replace('\n', ' ').lowercase(Locale.ENGLISH)
        val normalized = title.normalize()
        val providerTokens = KNOWN_PROVIDERS.filter { normalized.contains(it) }
        val resolutionTokens = RESOLUTION_PATTERN
            .findAll(title).map { it.value.lowercase(Locale.ENGLISH) }
        val codecTokens = CODEC_TOKEN_PATTERN
            .findAll(title).map { it.value.lowercase(Locale.ENGLISH).toCodecToken() }
        val audioTokens = sequence {
            if (DUAL_AUDIO_PATTERN.containsMatchIn(title)) yield("dual audio")
            if (DUBBED_PATTERN.containsMatchIn(title)) yield("dubbed")
        }
        val releaseGroupTokens = BRACKET_GROUP_PATTERN
            .findAll(video.videoTitle)
            .map { it.groupValues[1].trim().lowercase(Locale.ENGLISH) }
            .filter { it.any(Char::isLetter) }
            .filterNot { isMetaBracketTag(it) }
        return (providerTokens.asSequence() + resolutionTokens + codecTokens + audioTokens + releaseGroupTokens)
            .map { it.trim() }.filter { it.isNotBlank() }.toList()
    }

    private fun encode(fp: StreamFingerprint): String = listOf(
        VERSION_V4,
        fp.exactNorm,
        fp.batchNorm,
        fp.fileBaseNorm,
        fp.releaseGroup,
        fp.ripLabel,
        fp.qualityTag,
        fp.sizeBytes.toString(),
        fp.seeders.toString(),
        fp.provider,
        fp.hosterName,
    ).joinToString(FIELD_SEP.toString())

    private fun decode(selector: String): StreamFingerprint? {
        when {
            selector.startsWith("$VERSION_V4$FIELD_SEP") -> {
                val parts = selector.split(FIELD_SEP)
                if (parts.size < 11) return null
                return StreamFingerprint(
                    exactNorm = parts[1],
                    batchNorm = parts[2],
                    fileBaseNorm = parts[3],
                    releaseGroup = parts[4],
                    ripLabel = parts[5],
                    qualityTag = parts[6],
                    sizeBytes = parts[7].toLongOrNull() ?: -1L,
                    seeders = parts[8].toIntOrNull() ?: -1,
                    provider = parts[9],
                    hosterName = parts[10],
                )
            }
            selector.startsWith("$VERSION_V3$FIELD_SEP") -> {
                val parts = selector.split(FIELD_SEP)
                if (parts.size < 10) return null
                return StreamFingerprint(
                    exactNorm = parts[1],
                    batchNorm = parts[2],
                    fileBaseNorm = parts[3],
                    releaseGroup = parts[4],
                    ripLabel = parts[5],
                    qualityTag = parts[6],
                    sizeBytes = parts[7].toLongOrNull() ?: -1L,
                    seeders = parts[8].toIntOrNull() ?: -1,
                    provider = parts[9],
                    hosterName = "",
                )
            }
            selector.startsWith("$VERSION_V2$FIELD_SEP") -> {
                val parts = selector.split(FIELD_SEP)
                if (parts.size < 7) return null
                return StreamFingerprint(
                    exactNorm = parts[1],
                    batchNorm = parts[2],
                    fileBaseNorm = "",
                    releaseGroup = "",
                    ripLabel = parts[3],
                    qualityTag = parseQualityTag(parts[3]),
                    sizeBytes = parts[4].toLongOrNull() ?: -1L,
                    seeders = parts[5].toIntOrNull() ?: -1,
                    provider = parts[6],
                    hosterName = "",
                )
            }
            else -> return null
        }
    }

    private fun normalizeExact(text: String): String =
        text.lowercase(Locale.ENGLISH).replace(WHITESPACE_REGEX, " ").trim()

    private fun normalizeBatch(text: String): String {
        var normalized = normalizeExact(text)
        normalized = EPISODE_NUMBER_PATTERN.replace(normalized, " ")
        normalized = normalized.replace(NUMBER_TAG_PATTERN, " ")
        normalized = normalized.replace(BRACKETS_PARENS_PATTERN, " ")
        normalized = normalized.replace(WHITESPACE_REGEX, " ").trim()
        return normalized.normalize()
    }

    private fun normalizeFileBase(fileLine: String): String {
        var normalized = normalizeExact(fileLine)
        normalized = EPISODE_NUMBER_PATTERN.replace(normalized, " ")
        normalized = normalized.replace(NUMBER_TAG_PATTERN, " ")
        normalized = BRACKET_GROUP_PATTERN.replace(normalized, " ")
        normalized = normalized.replace(WHITESPACE_REGEX, " ").trim()
        return normalized.normalize()
    }

    private fun parsePrimaryReleaseGroup(vararg lines: String): String {
        for (line in lines) {
            for (match in BRACKET_GROUP_PATTERN.findAll(line)) {
                val group = match.groupValues[1].trim().lowercase(Locale.ENGLISH)
                if (group.isNotBlank() && !isMetaBracketTag(group)) {
                    return group.normalize()
                }
            }
        }
        return ""
    }

    private fun isMetaBracketTag(tag: String): Boolean {
        val lower = tag.lowercase(Locale.ENGLISH)
        return lower.contains(META_BRACKET_PATTERN) ||
            RESOLUTION_PATTERN.containsMatchIn(lower) ||
            lower in META_TAGS_SET
    }

    private fun parseQualityTag(text: String): String =
        RESOLUTION_PATTERN.find(text)?.value?.lowercase(Locale.ENGLISH) ?: ""

    private fun parseSizeBytes(text: String): Long {
        val sizeLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Size:", ignoreCase = true) }
        sizeLine?.let { line ->
            val match = SIZE_PATTERN.find(line)
            if (match != null) {
                return sizeMatchToBytes(match) ?: -1L
            }
        }
        return SIZE_PATTERN.findAll(text)
            .mapNotNull { sizeMatchToBytes(it) }
            .maxOrNull()
            ?: -1L
    }

    private fun sizeMatchToBytes(match: MatchResult): Long? {
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase(Locale.ENGLISH)) {
            "gb" -> (value * 1024 * 1024 * 1024).toLong()
            "mb" -> (value * 1024 * 1024).toLong()
            "kb" -> (value * 1024).toLong()
            else -> null
        }
    }

    private fun parseSeeders(text: String): Int =
        SEEDER_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: SEEDER_EMOJI_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: -1

    private fun parseProvider(text: String): String {
        val providerLine = text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("Provider:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.lowercase(Locale.ENGLISH)
        if (!providerLine.isNullOrBlank()) {
            val primary = providerLine.split('/', ',').firstOrNull()?.trim().orEmpty()
            if (primary.isNotBlank()) return primary.normalize()
        }

        val normalized = text.lowercase(Locale.ENGLISH).normalize()
        return KNOWN_PROVIDERS.firstOrNull { normalized.contains(it) } ?: ""
    }

    private fun sizeBytes(video: Video): Long = parseSizeBytes(video.videoTitle)

    fun videosEqual(a: Video, b: Video): Boolean =
        a.videoTitle == b.videoTitle &&
            a.videoUrl == b.videoUrl &&
            a.videoPageUrl == b.videoPageUrl

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format(Locale.ENGLISH, "%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ENGLISH, "%.2f KB", bytes / 1024.0)
    }

    private fun String.normalize(): String =
        lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun String.normalizeRip(): String =
        lowercase(Locale.ROOT).replace(WHITESPACE_REGEX, " ").trim()

    private fun String.toCodecToken(): String = when (this) {
        "h265", "265" -> "x265"
        "h264", "264" -> "x264"
        else -> this
    }

    fun getSelectorForHoster(compositeSelector: String, hosterName: String): String {
        if (compositeSelector.isBlank()) return ""
        val parts = compositeSelector.split(HOSTER_SEP)
        for (part in parts) {
            val idx = part.indexOf(HOSTER_KV_SEP)
            if (idx >= 0) {
                val name = part.substring(0, idx)
                val sel = part.substring(idx + 1)
                if (name.equals(hosterName, ignoreCase = true)) {
                    return sel
                }
            } else {
                val decoded = decode(part)
                if (decoded != null) {
                    if (decoded.hosterName.equals(hosterName, ignoreCase = true) || decoded.hosterName.isBlank()) {
                        return part
                    }
                } else {
                    return part
                }
            }
        }
        for (part in parts) {
            val idx = part.indexOf(HOSTER_KV_SEP)
            if (idx >= 0) {
                val name = part.substring(0, idx)
                val sel = part.substring(idx + 1)
                if (name.isBlank()) {
                    return sel
                }
            } else {
                val decoded = decode(part)
                if (decoded == null || decoded.hosterName.isBlank()) {
                    return part
                }
            }
        }
        return ""
    }

    fun updateCompositeSelector(compositeSelector: String, hosterName: String, newSelector: String): String {
        val parts = if (compositeSelector.isNotBlank()) {
            compositeSelector.split(HOSTER_SEP).toMutableList()
        } else {
            mutableListOf()
        }
        val newEntry = "$hosterName$HOSTER_KV_SEP$newSelector"
        var updated = false
        for (i in parts.indices) {
            val part = parts[i]
            val idx = part.indexOf(HOSTER_KV_SEP)
            if (idx >= 0) {
                val name = part.substring(0, idx)
                if (name.equals(hosterName, ignoreCase = true)) {
                    parts.removeAt(i)
                    parts.add(0, newEntry)
                    updated = true
                    break
                }
            } else {
                val decoded = decode(part)
                if (decoded != null && decoded.hosterName.equals(hosterName, ignoreCase = true)) {
                    parts.removeAt(i)
                    parts.add(0, newEntry)
                    updated = true
                    break
                }
            }
        }
        if (!updated) {
            parts.add(0, newEntry)
        }
        return parts.joinToString(HOSTER_SEP.toString())
    }
}
