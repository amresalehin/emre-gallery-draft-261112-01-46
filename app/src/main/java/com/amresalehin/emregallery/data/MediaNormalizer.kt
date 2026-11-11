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
    fun deduplicateMediaInboxItems(items: List<MediaInboxItem>): List<MediaInboxItem> {
        if (items.isEmpty()) return emptyList()
        val groups = items.groupBy { item ->
            val src = item.sourceUrl.ifBlank { item.localUri }
            canonicalKey(src).ifBlank { item.id }
        }
        val largestList = groups.values.map { group ->
            if (group.size == 1) group.first()
            else {
                group.maxWithOrNull { a, b ->
                    compareInboxItemQuality(a, b)
                } ?: group.first()
            }
        }
        val (videos, images) = largestList.partition { it.isVideo || it.isStream }
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
