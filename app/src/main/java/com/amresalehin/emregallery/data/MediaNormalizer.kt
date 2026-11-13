package com.amresalehin.emregallery.data

import java.util.Locale

/**
 * MediaNormalizer - The single source of truth for media candidate processing.
 *
 * Unifies:
 * 1. URL canonicalization and deduplication.
 * 2. Quality-based variant selection (preferring original master files and higher resolutions).
 * 3. Video-poster folding (deduplicating standalone poster images into their parent video).
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
     * Removes candidates that Pocket has already discovered or that are already represented
     * by an active/completed download. Bookmark records are authoritative even when they do
     * not have a localUri yet: discovery must not queue the same media a second time.
     *
     * FAILED activity records do not independently block candidates that are not already
     * retained in Pocket, preserving retry eligibility for those items.
     */
    fun filterNewQueueCandidates(
        candidates: List<ScrapedMediaItem>,
        existingBookmarkItems: List<MediaInboxItem>,
        downloadActivities: List<DownloadActivityItem>
    ): List<ScrapedMediaItem> {
        val protectedKeys = HashSet<String>()

        fun protect(url: String?) {
            if (url.isNullOrBlank()) return
            val key = canonicalKey(url)
            if (key.isNotBlank()) protectedKeys.add(key)
        }

        // Include every previously discovered Pocket item, not only items that have been
        // downloaded. Use the local URI only when no source URL was retained.
        existingBookmarkItems.forEach { item ->
            protect(item.sourceUrl)
            if (item.sourceUrl.isBlank()) protect(item.localUri)
        }

        // A queued download is recorded as DOWNLOADING. Also protect retries, paused jobs,
        // and completed downloads; leave FAILED entries retryable.
        val protectedStatuses = setOf(
            DownloadActivityStatus.DOWNLOADING,
            DownloadActivityStatus.RETRYING,
            DownloadActivityStatus.PAUSED,
            DownloadActivityStatus.FINISHED
        )
        downloadActivities.asSequence()
            .filter { it.status in protectedStatuses }
            .forEach { activity ->
                protect(activity.mediaUrl)
                if (activity.mediaUrl.isBlank()) protect(activity.localUri)
            }

        // Deduplicate within this discovery batch, then exclude anything already known by
        // Pocket or the download tracker using the same canonical identity function.
        return deduplicate(candidates).filter { candidate ->
            candidate.url.isNotBlank() && canonicalKey(candidate.url) !in protectedKeys
        }
    }

    /**
     * Compares quality between two candidates. Returns > 0 if candidate a is higher quality.
     */
    fun compareQuality(a: ScrapedMediaItem, b: ScrapedMediaItem): Int {
        return MediaClassifier.compareMediaQuality(a, b)
    }

    /**
     * Computes the canonical deduplication key for a media URL.
     * Strips only tracking parameters and fragments, groups CDN size variants,
     * and guards against over-merging.
     */
    fun canonicalKey(url: String): String {
        return MediaClassifier.deduplicationKey(url)
    }

    /**
     * Deduplicates a list of MediaInboxItem by grouping CDN size variants
     * by canonical key, and keeping the largest / highest-quality variant in each group.
     * Prevents over-merging by strictly preserving identifying query parameters.
     */
    fun deduplicateMediaInboxItems(items: List<MediaInboxItem>, pageUrl: String = ""): List<MediaInboxItem> {
        if (items.isEmpty()) return emptyList()
        val groups = items.groupBy { item ->
            val src = item.sourceUrl.ifBlank { item.localUri }
            canonicalKey(src).ifBlank { item.id }
        }
        val largestList = groups.values.map { group ->
            val winner = group.maxWithOrNull { a, b ->
                compareInboxItemQuality(a, b)
            } ?: group.first()
            val cached = group.firstOrNull { it.localUri.isNotBlank() }
            winner.copy(
                id = winner.id.ifBlank { cached?.id.orEmpty() },
                localUri = winner.localUri.ifBlank { cached?.localUri.orEmpty() },
                sizeBytes = maxOf(winner.sizeBytes, cached?.sizeBytes ?: 0L)
            )
        }
        // Pocket items are accumulated across incremental scans. Use their owning page URL
        // to pair platform-specific audio streams with the corresponding video, even when
        // the two tracks were discovered in different batches.
        val pairingCandidates = largestList.map { item ->
            val mediaUrl = item.sourceUrl.ifBlank { item.localUri }
            ScrapedMediaItem(
                id = item.id,
                url = mediaUrl,
                title = item.title,
                isVideo = item.isVideo,
                isAudio = item.isAudio,
                isStream = item.isStream,
                format = item.format,
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
        val withoutCompanionAudio = largestList.filterNot { item ->
            item.isAudio && item.sourceUrl.ifBlank { item.localUri } in pairedAudioUrls
        }

        val (videos, images) = withoutCompanionAudio.partition { it.isVideo || it.isStream }
        return (videos + images)
    }

    /**
     * Compares quality of two MediaInboxItem candidates.
     * Returns > 0 if a is higher quality (larger, video over image, higher resolution).
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

        // Master original URLs (/originals/)
        val aOrig = a.sourceUrl.contains("/originals/", ignoreCase = true)
        val bOrig = b.sourceUrl.contains("/originals/", ignoreCase = true)
        if (aOrig && !bOrig) return 1
        if (!aOrig && bOrig) return -1

        // Format preference
        val formatPriority = mapOf("PNG" to 4, "JPEG" to 3, "JPG" to 3, "WEBP" to 2, "AVIF" to 1)
        val aFmt = formatPriority[a.format.uppercase(Locale.ROOT)] ?: 0
        val bFmt = formatPriority[b.format.uppercase(Locale.ROOT)] ?: 0
        if (aFmt != bFmt) {
            return aFmt.compareTo(bFmt)
        }

        return 0
    }
}
