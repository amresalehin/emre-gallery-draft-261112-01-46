package com.amresalehin.emregallery.data

import java.io.File
import java.util.Locale

/**
 * MediaNormalizer - The single source of truth for media candidate processing.
 *
 * Unifies:
 * 1. URL canonicalization and multi-signal transitive deduplication (URLs, platform IDs, stems, local file hashes, sizes).
 * 2. Quality-based variant selection (preferring original master files, direct video over streams, and higher resolutions).
 * 3. Video-poster folding (deduplicating standalone poster images into their parent video's thumbnailUrl).
 * 4. Provenance merging (e.g. "Network + DOM").
 * 5. Download headers and resolution hint retention.
 */
object MediaNormalizer {

    /**
     * Builds and validates a clean ScrapedMediaItem using MediaCaptureEngine.
     */
    fun normalize(
        rawUrl: String,
        source: ExtractionSource,
        baseUrl: String,
        poster: String? = null,
        resolutionHint: String? = null,
        mimeHint: String? = null,
        indexHint: Int = 0,
        titleHint: String? = null,
        domSelector: String? = null,
        declaredWidth: Int? = null,
        declaredHeight: Int? = null,
        customId: String? = null
    ): ScrapedMediaItem? {
        return MediaCaptureEngine.buildMediaItem(
            rawUrl = rawUrl,
            source = source,
            baseUrl = baseUrl,
            poster = poster,
            resolutionHint = resolutionHint,
            mimeHint = mimeHint,
            indexHint = indexHint,
            titleHint = titleHint,
            domSelector = domSelector,
            declaredWidth = declaredWidth,
            declaredHeight = declaredHeight,
            customId = customId
        )
    }

    /**
     * Merges a list of incoming items into an existing mutable map keyed by canonical deduplicationKey.
     * Returns true if any item was added, replaced, or upgraded.
     */
    fun mergeCandidates(
        targetMap: MutableMap<String, ScrapedMediaItem>,
        incoming: List<ScrapedMediaItem>
    ): Boolean {
        return MediaClassifier.mergeMediaItems(targetMap, incoming)
    }

    /**
     * Deduplicates a list of scraped items, folding video poster images and retaining
     * the highest-quality master file for each media asset.
     */
    fun deduplicate(items: List<ScrapedMediaItem>): List<ScrapedMediaItem> {
        return MediaClassifier.deduplicateScrapedItems(items)
    }

    fun deduplicateList(items: List<ScrapedMediaItem>): List<ScrapedMediaItem> {
        return deduplicate(items)
    }

    /**
     * Compares quality between two candidates. Returns > 0 if candidate a is higher quality.
     */
    fun compareQuality(a: ScrapedMediaItem, b: ScrapedMediaItem): Int {
        return MediaClassifier.compareMediaQuality(a, b)
    }

    /**
     * Computes the canonical deduplication key for a media URL.
     * Strips tracking and ephemeral CDN params, groups CDN size variants,
     * and guards against over-merging.
     */
    fun canonicalKey(url: String): String {
        return MediaClassifier.deduplicationKey(url)
    }

    private fun extractInboxDedupSignals(item: MediaInboxItem, pageUrl: String): List<String> {
        val signals = LinkedHashSet<String>()
        val src = item.sourceUrl.ifBlank { item.localUri }
        val baseKey = canonicalKey(src).ifBlank { item.id }
        signals.add("canon:$baseKey")

        if (item.localUri.isNotBlank()) {
            val cleanLocal = item.localUri.removePrefix("file://").trim()
            if (cleanLocal.isNotBlank()) {
                signals.add("local:$cleanLocal")
                if (!item.localUri.startsWith("content://", ignoreCase = true)) {
                    MediaClassifier.computeFileSha256(File(cleanLocal))?.let { hash ->
                        signals.add("sha256:$hash")
                    }
                }
            }
        }

        if (item.isVideo || item.isStream) {
            MediaClassifier.extractTwitterVideoKey(src)?.let { signals.add(it) }
            val (gvId, _) = MediaClassifier.extractGoogleVideoIdAndItag(src)
            if (!gvId.isNullOrBlank()) {
                signals.add("gv:$gvId")
                val ytId = MediaClassifier.extractYouTubeVideoId(item.thumbnailUrl)
                    ?: MediaClassifier.extractYouTubeVideoId(src)
                    ?: MediaClassifier.extractYouTubeVideoId(pageUrl)
                if (!ytId.isNullOrBlank()) {
                    signals.add("ytvid:${ytId.lowercase(Locale.ROOT)}")
                }
            }
            MediaClassifier.videoStemKey(src)?.let { signals.add(it) }
        }

        MediaClassifier.specificMediaStemKey(src, item.isVideo || item.isStream, item.isAudio)?.let {
            signals.add(it)
        }

        if (item.sizeBytes > 16_384L) {
            val cleanFile = src.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
            if (cleanFile.length >= 4 && cleanFile !in setOf("image", "photo", "video", "media", "thumb", "thumbnail", "playlist", "master", "index", "videoplayback")) {
                val typeTag = when {
                    item.isVideo || item.isStream -> "V"
                    item.isAudio -> "A"
                    else -> "I"
                }
                signals.add("sizefile:$typeTag:${item.sizeBytes}:$cleanFile")
            }
        }

        return signals.toList()
    }

    /**
     * Deduplicates a list of MediaInboxItem by transitively grouping across canonical URL keys,
     * platform video IDs (YouTube multi-itag, Reddit v.redd.it, Twitter), stream/MP4 stem matches,
     * high-entropy filename stems, and local file SHA-256 hashes; retains cached local URIs & thumbnails;
     * folds companion audio tracks; and folds matching standalone poster images into parent videos.
     */
    fun deduplicateMediaInboxItems(items: List<MediaInboxItem>, pageUrl: String = ""): List<MediaInboxItem> {
        if (items.isEmpty()) return emptyList()

        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val firstIndexBySignal = HashMap<String, Int>(n * 2)
        for (i in 0 until n) {
            val signals = extractInboxDedupSignals(items[i], pageUrl)
            for (sig in signals) {
                val prev = firstIndexBySignal.putIfAbsent(sig, i)
                if (prev != null) {
                    union(prev, i)
                }
            }
        }

        val clusters = LinkedHashMap<Int, MutableList<MediaInboxItem>>()
        for (i in 0 until n) {
            clusters.getOrPut(find(i)) { mutableListOf() }.add(items[i])
        }

        val mergedList = clusters.values.map { group ->
            val winner = group.maxWithOrNull { a, b ->
                compareInboxItemQuality(a, b)
            } ?: group.first()
            val cached = group.firstOrNull { it.localUri.isNotBlank() }
            val bestThumb = group.firstNotNullOfOrNull { candidate ->
                candidate.thumbnailUrl.takeIf { it.isNotBlank() }
            }.orEmpty()
            val bestSize = group.maxOfOrNull { it.sizeBytes } ?: 0L
            winner.copy(
                id = winner.id.ifBlank { cached?.id.orEmpty() },
                localUri = winner.localUri.ifBlank { cached?.localUri.orEmpty() },
                sizeBytes = maxOf(winner.sizeBytes, bestSize),
                thumbnailUrl = winner.thumbnailUrl.ifBlank { bestThumb }
            )
        }

        // Fold platform-specific companion audio streams (e.g. YouTube DASH audio, Reddit DASH_AUDIO)
        val pairingCandidates = mergedList.map { item ->
            val mediaUrl = item.sourceUrl.ifBlank { item.localUri }
            ScrapedMediaItem(
                id = item.id,
                url = mediaUrl,
                title = item.title,
                isVideo = item.isVideo,
                isAudio = item.isAudio,
                isStream = item.isStream,
                format = item.format,
                thumbnailUrl = item.thumbnailUrl.takeIf { it.isNotBlank() },
                provenance = MediaProvenance(pageUrl = pageUrl),
                metadata = if (pageUrl.isBlank()) emptyMap() else mapOf("source_url" to pageUrl)
            )
        }
        val pairedAudioUrls = pairingCandidates
            .filter { it.isAudio }
            .filter { audio ->
                pairingCandidates.any { video ->
                    video.url != audio.url && MediaClassifier.isSeparateAudioCompanion(audio, video)
                }
            }
            .map { it.url }
            .toSet()
        val withoutCompanionAudio = mergedList.filterNot { item ->
            item.isAudio && item.sourceUrl.ifBlank { item.localUri } in pairedAudioUrls
        }

        // Fold standalone video poster images into the parent video's thumbnailUrl
        val (videosRaw, nonVideos) = withoutCompanionAudio.partition { it.isVideo || it.isStream }
        if (videosRaw.isEmpty()) return nonVideos

        val consumedPosterIndices = HashSet<Int>()
        val upgradedVideos = videosRaw.map { video ->
            val vidSrc = video.sourceUrl.ifBlank { video.localUri }
            val vidThumbKey = video.thumbnailUrl.takeIf { it.isNotBlank() }?.let { canonicalKey(it) }
            val twVidKey = MediaClassifier.extractTwitterVideoKey(vidSrc)
            val ytId = MediaClassifier.extractYouTubeVideoId(vidSrc)
                ?: video.thumbnailUrl.takeIf { it.isNotBlank() }?.let { MediaClassifier.extractYouTubeVideoId(it) }
                ?: if (vidSrc.contains("googlevideo.com", ignoreCase = true) || vidSrc.contains("/videoplayback", ignoreCase = true)) {
                    MediaClassifier.extractYouTubeVideoId(pageUrl)
                } else null
            val ytThumbKey = ytId?.let { "ytthumb:${it.lowercase(Locale.ROOT)}" }

            var chosenThumb = video.thumbnailUrl
            nonVideos.forEachIndexed { idx, img ->
                if (!img.isAudio) {
                    val imgSrc = img.sourceUrl.ifBlank { img.localUri }
                    val imgKey = canonicalKey(imgSrc)
                    val isPosterMatch = (vidThumbKey != null && imgKey == vidThumbKey) ||
                        (ytThumbKey != null && imgKey == ytThumbKey) ||
                        (twVidKey != null && MediaClassifier.extractTwitterThumbVideoKey(imgSrc) == twVidKey)
                    if (isPosterMatch) {
                        consumedPosterIndices.add(idx)
                        val candidateThumbUrl = img.thumbnailUrl.ifBlank { img.sourceUrl.ifBlank { img.localUri } }
                        if (chosenThumb.isBlank()) {
                            chosenThumb = candidateThumbUrl
                        } else {
                            val currentArea = MediaClassifier.parsePixelArea(null, chosenThumb) ?: 0L
                            val newArea = MediaClassifier.parsePixelArea(null, candidateThumbUrl) ?: 0L
                            if (newArea > currentArea || (newArea == currentArea && img.sizeBytes > 0L)) {
                                chosenThumb = candidateThumbUrl
                            }
                        }
                    }
                }
            }
            if (chosenThumb.isBlank() && !ytId.isNullOrBlank()) {
                chosenThumb = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
            }
            if (chosenThumb != video.thumbnailUrl) video.copy(thumbnailUrl = chosenThumb) else video
        }

        val remainingImages = nonVideos.filterIndexed { idx, _ -> idx !in consumedPosterIndices }
        return (upgradedVideos + remainingImages)
    }

    /**
     * Compares quality of two MediaInboxItem candidates.
     * Returns > 0 if a is higher quality (video over image, higher resolution, larger size, direct over stream).
     */
    fun compareInboxItemQuality(a: MediaInboxItem, b: MediaInboxItem): Int {
        // Videos and streams take priority over images
        if ((a.isVideo || a.isStream) && (!b.isVideo && !b.isStream)) return 1
        if ((!a.isVideo && !a.isStream) && (b.isVideo || b.isStream)) return -1

        // Compare parsed pixel areas
        val aArea = MediaClassifier.parsePixelArea(null, a.sourceUrl)
        val bArea = MediaClassifier.parsePixelArea(null, b.sourceUrl)
        if (aArea != null && bArea != null && aArea != bArea) {
            return aArea.compareTo(bArea)
        }
        if (aArea != null && bArea == null) return 1
        if (aArea == null && bArea != null) return -1

        // Compare sizeBytes if present
        if (a.sizeBytes > 0L || b.sizeBytes > 0L) {
            val cmp = a.sizeBytes.compareTo(b.sizeBytes)
            if (cmp != 0) return cmp
        }

        // Prefer direct playable video file over streaming manifest when resolution & size are equal
        if ((a.isVideo || a.isStream) && (b.isVideo || b.isStream) && a.isStream != b.isStream) {
            return if (!a.isStream) 1 else -1
        }

        // Master original URLs (/originals/)
        val aOrig = a.sourceUrl.contains("/originals/", ignoreCase = true)
        val bOrig = b.sourceUrl.contains("/originals/", ignoreCase = true)
        if (aOrig && !bOrig) return 1
        if (!aOrig && bOrig) return -1

        // Format preference
        val formatPriority = mapOf("PNG" to 4, "JPEG" to 3, "JPG" to 3, "WEBP" to 2, "AVIF" to 1, "MP4" to 4, "MOV" to 3, "WEBM" to 2)
        val aFmt = formatPriority[a.format.uppercase(Locale.ROOT)] ?: 0
        val bFmt = formatPriority[b.format.uppercase(Locale.ROOT)] ?: 0
        if (aFmt != bFmt) {
            return aFmt.compareTo(bFmt)
        }

        // Prefer item that already has a local cache or thumbnail URL
        if (a.localUri.isNotBlank() && b.localUri.isBlank()) return 1
        if (a.localUri.isBlank() && b.localUri.isNotBlank()) return -1
        if (a.thumbnailUrl.isNotBlank() && b.thumbnailUrl.isBlank()) return 1
        if (a.thumbnailUrl.isBlank() && b.thumbnailUrl.isNotBlank()) return -1

        return 0
    }
}

