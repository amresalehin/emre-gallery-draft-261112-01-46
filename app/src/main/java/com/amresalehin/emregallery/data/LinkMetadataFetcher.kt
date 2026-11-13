package com.amresalehin.emregallery.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URI
import java.util.Locale

data class LinkMetadata(
    val url: String,
    val title: String = "",
    val description: String = "",
    val previewImageUrl: String = "",
    val siteName: String = "",
    val domain: String = "",
    val faviconUrl: String = "",
    val mediaItems: List<MediaInboxItem> = emptyList()
)

/**
 * High-performance parser and automatic fetcher for web link titles, descriptions, previews,
 * and all site media items (images, artwork, video posters).
 * Extracts standard Open Graph, Twitter Cards, JSON-LD Schema.org metadata, document metadata,
 * and scans full DOM media collections.
 */
object LinkMetadataFetcher {
    private const val TAG = "LinkMetadataFetcher"

    /**
     * Fetches metadata and all media items from a web URL asynchronously.
     */
    suspend fun fetch(rawUrl: String): LinkMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return@withContext LinkMetadata(url = rawUrl)
        }

        val normalizedUrl = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val domain = MediaClassifier.cleanDomainFromUrl(normalizedUrl)
        val defaultFavicon = if (domain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=$domain&sz=128"
        } else ""

        val highResSiteIcon = if (domain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=$domain&sz=256"
        } else ""

        // 1. Specialized Extractor Engine (yt-dlp primary, gallery-dl secondary)
        if (GalleryDlEngine.isAvailable() && GalleryDlEngine.checkSupport(normalizedUrl).isSupported) {
            try {
                val gdlRes = GalleryDlEngine.extractMedia(normalizedUrl)
                if (gdlRes.success && gdlRes.items.isNotEmpty()) {
                    val deduplicatedItems = MediaClassifier.deduplicateScrapedItems(gdlRes.items)
                    val mediaItems = deduplicatedItems.map { item ->
                        MediaInboxItem(
                            id = "gdl_${item.id}",
                            title = item.title,
                            localUri = "",
                            sourceUrl = item.url,
                            isVideo = item.isVideo,
                            isAudio = item.isAudio,
                            isStream = item.isStream,
                            format = item.format,
                            sizeBytes = item.sizeBytes ?: 0L,
                            dateAddedMillis = System.currentTimeMillis()
                        )
                    }
                    val preview = gdlRes.previewImageUrl.ifBlank {
                        deduplicatedItems.firstOrNull { !it.isVideo && !it.isAudio }?.url
                            ?: deduplicatedItems.firstOrNull()?.thumbnailUrl
                            ?: deduplicatedItems.firstOrNull()?.url
                            ?: highResSiteIcon
                    }
                    val cleanTitle = gdlRes.pageTitle.ifBlank { domain }
                    val cleanDesc = gdlRes.pageDescription.ifBlank { "Discovered via ${gdlRes.extractorName} (${mediaItems.size} items)" }

                    return@withContext LinkMetadata(
                        url = normalizedUrl,
                        title = cleanTitle,
                        description = cleanDesc,
                        previewImageUrl = preview,
                        siteName = gdlRes.extractorName.ifBlank { domain },
                        domain = domain,
                        faviconUrl = defaultFavicon,
                        mediaItems = mediaItems
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "GalleryDlEngine extraction attempt failed for $normalizedUrl: ${e.message}")
            }
        }

        try {
            val response = OkHttpDownloader.getHtml(normalizedUrl, maxBytes = 8 * 1024 * 1024L)
            val finalUrl = response.finalUrl.ifBlank { normalizedUrl }
            val finalDomain = MediaClassifier.cleanDomainFromUrl(finalUrl).ifBlank { domain }

            // Handle direct image responses
            val cleanLower = finalUrl.lowercase(Locale.ROOT).substringBefore('?').substringBefore('#')
            val isDirectImage = response.contentType.startsWith("image/") ||
                    cleanLower.endsWith(".jpg") || cleanLower.endsWith(".jpeg") ||
                    cleanLower.endsWith(".png") || cleanLower.endsWith(".webp") ||
                    cleanLower.endsWith(".gif") || cleanLower.endsWith(".avif") ||
                    cleanLower.endsWith(".bmp") || cleanLower.endsWith(".svg")

            val isDirectVideo = response.contentType.startsWith("video/") ||
                    cleanLower.endsWith(".mp4") || cleanLower.endsWith(".webm") ||
                    cleanLower.endsWith(".mov") || cleanLower.endsWith(".mkv") ||
                    cleanLower.endsWith(".m3u8") || cleanLower.endsWith(".mpd")

            if (isDirectVideo) {
                val videoName = finalUrl.substringAfterLast('/').substringBefore('?').ifBlank { finalDomain }
                val singleItem = MediaInboxItem(
                    id = "direct_${finalUrl.hashCode().toUInt().toString(16)}",
                    title = videoName,
                    localUri = "",
                    sourceUrl = finalUrl,
                    isVideo = true,
                    format = response.contentType.substringAfter('/').ifBlank { cleanLower.substringAfterLast('.') }
                )
                return@withContext LinkMetadata(
                    url = finalUrl,
                    title = videoName,
                    description = "Direct Video",
                    previewImageUrl = finalUrl,
                    siteName = finalDomain,
                    domain = finalDomain,
                    faviconUrl = defaultFavicon,
                    mediaItems = listOf(singleItem)
                )
            }

            if (isDirectImage) {
                val imageName = finalUrl.substringAfterLast('/').substringBefore('?').ifBlank { finalDomain }
                val singleItem = MediaInboxItem(
                    id = "direct_${finalUrl.hashCode().toUInt().toString(16)}",
                    title = imageName,
                    localUri = "",
                    sourceUrl = finalUrl,
                    isVideo = false,
                    format = response.contentType.substringAfter('/').ifBlank { cleanLower.substringAfterLast('.') }
                )
                return@withContext LinkMetadata(
                    url = finalUrl,
                    title = imageName,
                    description = "Direct Image (${response.contentType.substringAfter('/')})",
                    previewImageUrl = finalUrl,
                    siteName = finalDomain,
                    domain = finalDomain,
                    faviconUrl = defaultFavicon,
                    mediaItems = listOf(singleItem)
                )
            }

            if (!response.isSuccessful || response.bodyString.isNullOrBlank()) {
                if (GalleryDlEngine.isAvailable()) {
                    val gdlRes = runCatching { GalleryDlEngine.extractMedia(finalUrl) }.getOrNull()
                    if (gdlRes != null && gdlRes.success && gdlRes.items.isNotEmpty()) {
                        val mediaItems = gdlRes.items.map { item ->
                            MediaInboxItem(
                                id = "gdl_${item.id}",
                                title = item.title,
                                localUri = "",
                                sourceUrl = item.url,
                                isVideo = item.isVideo,
                                isAudio = item.isAudio,
                                isStream = item.isStream,
                                format = item.format,
                                sizeBytes = item.sizeBytes ?: 0L,
                                dateAddedMillis = System.currentTimeMillis()
                            )
                        }
                        val preview = gdlRes.previewImageUrl.ifBlank {
                            gdlRes.items.firstOrNull { !it.isVideo }?.url
                                ?: gdlRes.items.firstOrNull()?.thumbnailUrl
                                ?: gdlRes.items.firstOrNull()?.url
                                ?: highResSiteIcon
                        }
                        val cleanTitle = gdlRes.pageTitle.ifBlank { finalDomain }
                        val cleanDesc = gdlRes.pageDescription.ifBlank { "Discovered via ${gdlRes.extractorName} (${mediaItems.size} items)" }

                        return@withContext LinkMetadata(
                            url = finalUrl,
                            title = cleanTitle,
                            description = cleanDesc,
                            previewImageUrl = preview,
                            siteName = gdlRes.extractorName.ifBlank { finalDomain },
                            domain = finalDomain,
                            faviconUrl = defaultFavicon,
                            mediaItems = mediaItems
                        )
                    }
                }

                val fallbackItem = if (highResSiteIcon.isNotBlank()) {
                    listOf(
                        MediaInboxItem(
                            id = "site_icon_${finalDomain.hashCode().toUInt().toString(16)}",
                            title = finalDomain,
                            localUri = "",
                            sourceUrl = highResSiteIcon,
                            isVideo = false,
                            format = "PNG"
                        )
                    )
                } else emptyList()

                return@withContext LinkMetadata(
                    url = finalUrl,
                    title = finalDomain.ifBlank { finalUrl },
                    description = "",
                    previewImageUrl = highResSiteIcon,
                    siteName = finalDomain,
                    domain = finalDomain,
                    faviconUrl = defaultFavicon,
                    mediaItems = fallbackItem
                )
            }

            parseHtml(response.bodyString, finalUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching link metadata for $normalizedUrl", e)
            val fallbackItem = if (highResSiteIcon.isNotBlank()) {
                listOf(
                    MediaInboxItem(
                        id = "site_icon_${domain.hashCode().toUInt().toString(16)}",
                        title = domain,
                        localUri = "",
                        sourceUrl = highResSiteIcon,
                        isVideo = false,
                        format = "PNG"
                    )
                )
            } else emptyList()

            LinkMetadata(
                url = normalizedUrl,
                title = domain.ifBlank { normalizedUrl },
                description = "",
                previewImageUrl = highResSiteIcon,
                siteName = domain,
                domain = domain,
                faviconUrl = defaultFavicon,
                mediaItems = fallbackItem
            )
        }
    }

    private data class JsonLdMetadata(
        val title: String = "",
        val description: String = "",
        val imageUrl: String = "",
        val siteName: String = ""
    )

    /**
     * Parses application/ld+json script tags (used heavily by modern SPAs, Next.js, Magnific, Shopify).
     */
    private fun parseJsonLd(doc: Document): JsonLdMetadata {
        val scripts = doc.select("script[type='application/ld+json']")
        for (script in scripts) {
            val raw = script.data().trim().ifBlank { script.html().trim() }
            if (raw.isBlank()) continue

            try {
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val parsed = extractFromJsonLdObject(obj)
                        if (parsed.title.isNotBlank() || parsed.imageUrl.isNotBlank()) return parsed
                    }
                } else if (raw.startsWith("{")) {
                    val obj = JSONObject(raw)
                    val graph = obj.optJSONArray("@graph")
                    if (graph != null) {
                        for (i in 0 until graph.length()) {
                            val item = graph.optJSONObject(i) ?: continue
                            val parsed = extractFromJsonLdObject(item)
                            if (parsed.title.isNotBlank() || parsed.imageUrl.isNotBlank()) return parsed
                        }
                    } else {
                        val parsed = extractFromJsonLdObject(obj)
                        if (parsed.title.isNotBlank() || parsed.imageUrl.isNotBlank()) return parsed
                    }
                }
            } catch (_: Exception) {}
        }
        return JsonLdMetadata()
    }

    private fun extractFromJsonLdObject(obj: JSONObject): JsonLdMetadata {
        val type = obj.optString("@type", "")
        val isRelevant = type in listOf("WebPage", "WebSite", "Article", "NewsArticle", "BlogPosting", "Organization", "SoftwareApplication", "Product", "") ||
                obj.has("headline") || obj.has("name")

        if (!isRelevant) return JsonLdMetadata()

        val title = obj.optString("headline", "").ifBlank { obj.optString("name", "") }
        val description = obj.optString("description", "")
        var image = ""

        val rawImg = obj.opt("image")
        if (rawImg is String && rawImg.isNotBlank()) {
            image = rawImg
        } else if (rawImg is JSONObject) {
            image = rawImg.optString("url", "")
        } else if (rawImg is JSONArray && rawImg.length() > 0) {
            val first = rawImg.opt(0)
            if (first is String) image = first
            else if (first is JSONObject) image = first.optString("url", "")
        }

        if (image.isBlank()) {
            image = obj.optString("thumbnailUrl", "").ifBlank { obj.optString("logo", "") }
        }

        val publisher = obj.optJSONObject("publisher")
        val siteName = publisher?.optString("name", "") ?: ""

        return JsonLdMetadata(
            title = title,
            description = description,
            imageUrl = image,
            siteName = siteName
        )
    }

    /**
     * Parses raw HTML string and extracts OpenGraph, Twitter, JSON-LD, document metadata,
     * and all discovered site media items.
     * Pure and isolated for reliable testing.
     */
    fun parseSrcsetUrls(srcset: String): List<String> {
        if (srcset.isBlank()) return emptyList()
        return srcset.split(",")
            .map { it.trim().split("\\s+".toRegex()).firstOrNull() ?: "" }
            .filter { it.isNotBlank() }
    }

    /**
     * Selects one best responsive image candidate instead of turning every srcset
     * resolution into a separate Pocket media item.
     */
    fun selectBestSrcsetUrl(srcset: String): String? {
        if (srcset.isBlank()) return null
        return srcset.split(",").mapNotNull { entry ->
            val parts = entry.trim().split("\\s+".toRegex())
            val candidateUrl = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val descriptor = parts.getOrNull(1).orEmpty()
            val score = when {
                descriptor.endsWith("w", ignoreCase = true) ->
                    descriptor.dropLast(1).toDoubleOrNull() ?: 0.0
                descriptor.endsWith("x", ignoreCase = true) ->
                    (descriptor.dropLast(1).toDoubleOrNull() ?: 0.0) * 1000.0
                else -> 0.0
            }
            candidateUrl to score
        }.maxByOrNull { it.second }?.first
    }

    /**
     * Parses raw HTML string and extracts OpenGraph, Twitter, JSON-LD, document metadata,
     * and all discovered site media items with full <base href> resolution.
     * Pure and isolated for reliable testing.
     */
    fun parseHtml(html: String, targetUrl: String): LinkMetadata {
        val domain = MediaClassifier.cleanDomainFromUrl(targetUrl)
        val defaultFavicon = if (domain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=$domain&sz=128"
        } else ""

        val doc: Document = Jsoup.parse(html, targetUrl)

        // Resolve against <base href> and the redirect URL
        val baseTagHref = doc.selectFirst("base[href]")?.attr("href")?.trim()
        val effectiveBaseUrl = if (!baseTagHref.isNullOrBlank()) {
            resolveAbsoluteUrl(targetUrl, baseTagHref)
        } else {
            targetUrl
        }
        doc.setBaseUri(effectiveBaseUrl)

        val jsonLd = parseJsonLd(doc)

        // 1. Title Extraction
        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name='og:title']")?.attr("content")?.trim()
        val twitterTitle = doc.selectFirst("meta[name='twitter:title']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property='twitter:title']")?.attr("content")?.trim()
        val docTitle = doc.title().trim()
        val metaTitle = doc.selectFirst("meta[name='title']")?.attr("content")?.trim()
        val itempropName = doc.selectFirst("meta[itemprop='name']")?.attr("content")?.trim()
        val h1Title = doc.selectFirst("h1")?.text()?.trim()

        val candidateTitle = sequenceOf(ogTitle, twitterTitle, jsonLd.title.takeIf { it.isNotBlank() }, docTitle, metaTitle, itempropName, h1Title)
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && !BotDetectionMitigator.isBotChallenge(it, effectiveBaseUrl) }
            ?: domain.ifBlank { effectiveBaseUrl }

        val cleanTitle = cleanText(candidateTitle)

        // 2. Description Extraction
        val ogDesc = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name='og:description']")?.attr("content")?.trim()
        val twitterDesc = doc.selectFirst("meta[name='twitter:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property='twitter:description']")?.attr("content")?.trim()
        val metaDesc = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property='description']")?.attr("content")?.trim()
        val itempropDesc = doc.selectFirst("meta[itemprop='description']")?.attr("content")?.trim()
        val firstParagraph = doc.select("p").asSequence()
            .map { it.text().trim() }
            .firstOrNull { it.length in 30..400 && !it.contains("cookies", ignoreCase = true) }

        val candidateDesc = sequenceOf(ogDesc, twitterDesc, jsonLd.description.takeIf { it.isNotBlank() }, metaDesc, itempropDesc, firstParagraph)
            .firstOrNull { !it.isNullOrBlank() }
            ?: ""

        val cleanDesc = cleanText(candidateDesc)

        // 3. Site Name
        val ogSiteName = doc.selectFirst("meta[property='og:site_name']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name='application-name']")?.attr("content")?.trim()
        val siteName = ogSiteName?.ifBlank { null }
            ?: jsonLd.siteName.takeIf { it.isNotBlank() }
            ?: domain

        // 4. Preview Image Extraction
        val ogImage = doc.selectFirst("meta[property='og:image']")?.let { extractAbsUrl(it, "content") }
            ?: doc.selectFirst("meta[name='og:image']")?.let { extractAbsUrl(it, "content") }
        val ogImageSecure = doc.selectFirst("meta[property='og:image:secure_url']")?.let { extractAbsUrl(it, "content") }
            ?: doc.selectFirst("meta[property='og:image:url']")?.let { extractAbsUrl(it, "content") }
        val twitterImage = doc.selectFirst("meta[name='twitter:image']")?.let { extractAbsUrl(it, "content") }
            ?: doc.selectFirst("meta[name='twitter:image:src']")?.let { extractAbsUrl(it, "content") }
            ?: doc.selectFirst("meta[property='twitter:image']")?.let { extractAbsUrl(it, "content") }
        val linkImageSrc = doc.selectFirst("link[rel='image_src']")?.let { extractAbsUrl(it, "href") }
        val thumbnailMeta = doc.selectFirst("meta[name='thumbnail']")?.let { extractAbsUrl(it, "content") }
        val itempropImage = doc.selectFirst("meta[itemprop='image']")?.let { extractAbsUrl(it, "content") }
            ?: doc.selectFirst("link[itemprop='image']")?.let { extractAbsUrl(it, "href") }
        val appleTouchIcon = doc.selectFirst("link[rel='apple-touch-icon']")?.let { extractAbsUrl(it, "href") }
            ?: doc.selectFirst("link[rel='apple-touch-icon-precomposed']")?.let { extractAbsUrl(it, "href") }
        val hiResIcon = doc.selectFirst("link[rel~=(?i)icon][sizes~=(?i)(192x192|256x256|512x512)]")?.let { extractAbsUrl(it, "href") }

        val candidateImage = sequenceOf(
            ogImage,
            ogImageSecure,
            twitterImage,
            jsonLd.imageUrl.takeIf { it.isNotBlank() },
            linkImageSrc,
            thumbnailMeta,
            itempropImage,
            appleTouchIcon,
            hiResIcon
        ).firstOrNull { !it.isNullOrBlank() } ?: ""

        val rawResolvedPreview = resolveAbsoluteUrl(effectiveBaseUrl, candidateImage)
        val previewImageUrl = if (rawResolvedPreview.isNotBlank()) {
            rawResolvedPreview
        } else if (domain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=$domain&sz=256"
        } else ""

        // 5. Favicon Extraction
        val faviconEl = doc.selectFirst("link[rel='shortcut icon']")
            ?: doc.selectFirst("link[rel='icon']")
            ?: doc.selectFirst("link[rel~=(?i)icon]")
        val extractedFavicon = faviconEl?.let { extractAbsUrl(it, "href") } ?: ""
        val resolvedFavicon = resolveAbsoluteUrl(effectiveBaseUrl, extractedFavicon)
        val finalFavicon = if (resolvedFavicon.isNotBlank()) resolvedFavicon else defaultFavicon

        // 6. Extract all media items of the site
        val mediaItems = extractSiteMedia(doc, effectiveBaseUrl, cleanTitle, previewImageUrl, jsonLd.imageUrl)

        return LinkMetadata(
            url = effectiveBaseUrl,
            title = cleanTitle,
            description = cleanDesc,
            previewImageUrl = previewImageUrl,
            siteName = siteName,
            domain = domain,
            faviconUrl = finalFavicon,
            mediaItems = mediaItems
        )
    }

    /**
     * Extracts all media items (images, photos, videos, streams) present across the site/document.
     * Parses <picture>, srcset, data-lazy-src, data-original, <noscript>, <script> JSON-LD / __NEXT_DATA__,
     * background-image styles, and expands .m3u8 playlists.
     */
    fun extractSiteMedia(
        doc: Document,
        baseUrl: String,
        defaultTitle: String = "",
        primaryPreviewUrl: String = "",
        jsonLdImageUrl: String = ""
    ): List<MediaInboxItem> {
        val items = mutableListOf<MediaInboxItem>()
        val seenUrls = mutableSetOf<String>()

        fun addCandidate(
            rawUrl: String?,
            titleHint: String?,
            isVideo: Boolean = false,
            isAudio: Boolean = false,
            allowExtensionlessMedia: Boolean = false,
            mimeHint: String? = null,
            declaredWidth: Int? = null,
            declaredHeight: Int? = null
        ) {
            if (rawUrl.isNullOrBlank()) return
            val resolved = resolveAbsoluteUrl(baseUrl, rawUrl).trim()
            if (resolved.isBlank() || !resolved.startsWith("http", ignoreCase = true)) return
            if (seenUrls.contains(resolved)) return
            if (!MediaClassifier.isLikelyMediaCandidate(
                    resolved,
                    mimeHint = mimeHint,
                    allowExtensionlessMedia = allowExtensionlessMedia || isVideo || isAudio
                )
            ) return

            val lower = resolved.lowercase(Locale.ROOT)
            val pathName = lower.substringBefore('?').substringBefore('#').substringAfterLast('/')
            // Favicons, app icons and common UI chrome are previews, not site media.
            if (lower.contains("google.com/s2/favicons") ||
                pathName.startsWith("apple-touch-icon") ||
                pathName.startsWith("favicon") ||
                Regex("""^(?:logo|icon|avatar|badge|emoji|spinner|placeholder|sprite)(?:[-_.@]|$)""").containsMatchIn(pathName)
            ) return

            // Explicit dimensions are a reliable signal for tiny UI assets. Do not guess
            // dimensions for unknown CDN URLs; those remain eligible for user filters.
            if ((declaredWidth != null && declaredWidth in 1..95) ||
                (declaredHeight != null && declaredHeight in 1..95)
            ) return

            seenUrls.add(resolved)
            val cleanTitle = titleHint?.takeIf { it.isNotBlank() }
                ?: pathName.takeIf { it.isNotBlank() && it.length > 2 }
                ?: defaultTitle.ifBlank { "Media ${items.size + 1}" }

            val classification = MediaClassifier.classify(resolved, mimeHint)
            val isM3u8 = lower.contains(".m3u8") || lower.substringBefore('?').endsWith(".mpd") ||
                classification.isStream
            val isActualAudio = isAudio || classification.isAudio
            val isActualVideo = !isActualAudio && (isVideo || classification.isVideo || isM3u8)
            val format = when {
                isM3u8 && lower.contains(".mpd") -> "MPD"
                isM3u8 -> "M3U8"
                isActualAudio || isActualVideo -> classification.format
                else -> classification.format
            }

            items.add(
                MediaInboxItem(
                    id = "site_media_${items.size}_${resolved.hashCode().toUInt().toString(16)}",
                    title = cleanTitle,
                    localUri = "",
                    sourceUrl = resolved,
                    isVideo = isActualVideo,
                    isAudio = isActualAudio,
                    isStream = isM3u8,
                    format = format,
                    dateAddedMillis = System.currentTimeMillis()
                )
            )
        }

        fun processImageElement(img: org.jsoup.nodes.Element, defaultAlt: String) {
            val alt = img.attr("alt").ifBlank { img.attr("title") }.ifBlank { defaultAlt }
            val classAndId = "${img.className()} ${img.id()}".lowercase(Locale.ROOT)
            if (Regex("""(?:^|[\s_-])(?:logo|icon|avatar|emoji|badge|spinner|placeholder|sprite)(?:$|[\s_-])""")
                    .containsMatchIn(classAndId)
            ) return

            val declaredWidth = img.attr("width").toIntOrNull()
            val declaredHeight = img.attr("height").toIntOrNull()
            if ((declaredWidth != null && declaredWidth in 1..95) ||
                (declaredHeight != null && declaredHeight in 1..95)
            ) return

            val fullResolution = listOf(
                "data-hi-res-src", "data-large-file", "data-original",
                "data-zoom-src", "data-full-src"
            ).firstNotNullOfOrNull { attr -> img.attr(attr).trim().takeIf { it.isNotBlank() } }
            val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            val selectedSrcset = selectBestSrcsetUrl(srcset)
            val lazyFallback = listOf("data-src", "data-lazy-src", "data-url", "src")
                .firstNotNullOfOrNull { attr -> img.attr(attr).trim().takeIf { it.isNotBlank() } }
            val selected = fullResolution ?: selectedSrcset ?: lazyFallback
            if (selected.isNullOrBlank()) return

            addCandidate(
                selected,
                alt,
                allowExtensionlessMedia = true,
                declaredWidth = declaredWidth,
                declaredHeight = declaredHeight
            )
        }

        // 1. Primary preview image always gets position 1 in the deck
        if (primaryPreviewUrl.isNotBlank()) {
            addCandidate(primaryPreviewUrl, defaultTitle.ifBlank { "Cover Image" }, allowExtensionlessMedia = true)
        }

        // 2. JSON-LD Image if distinct
        if (jsonLdImageUrl.isNotBlank()) {
            addCandidate(jsonLdImageUrl, defaultTitle, allowExtensionlessMedia = true)
        }

        // 3. Additional OpenGraph & Twitter Images
        doc.select("meta[property='og:image'], meta[name='og:image'], meta[property='og:image:secure_url'], meta[property='og:image:url']").forEach {
            val u = it.absUrl("content").ifBlank { it.attr("content") }
            addCandidate(u, defaultTitle, allowExtensionlessMedia = true)
        }
        doc.select("meta[name='twitter:image'], meta[name='twitter:image:src'], meta[property='twitter:image']").forEach {
            val u = it.absUrl("content").ifBlank { it.attr("content") }
            addCandidate(u, defaultTitle)
        }

        // 4. Videos & Posters (OpenGraph, Twitter video, and HTML5 video tags)
        doc.select("meta[property='og:video'], meta[property='og:video:url'], meta[property='og:video:secure_url'], meta[name='twitter:player:stream']").forEach {
            val u = it.absUrl("content").ifBlank { it.attr("content") }
            if (u.isNotBlank()) addCandidate(u, defaultTitle.ifBlank { "Video" }, isVideo = true)
        }
        doc.select("video").forEach { vid ->
            val src = vid.absUrl("src").ifBlank { vid.attr("src") }
                .ifBlank { vid.absUrl("data-src").ifBlank { vid.attr("data-src") } }
                .ifBlank { vid.absUrl("data-video-src").ifBlank { vid.attr("data-video-src") } }
            if (src.isNotBlank()) addCandidate(src, "Video", isVideo = true)
            vid.select("source").forEach { s ->
                val sSrc = s.absUrl("src").ifBlank { s.attr("src") }
                    .ifBlank { s.absUrl("data-src").ifBlank { s.attr("data-src") } }
                if (sSrc.isNotBlank()) addCandidate(sSrc, "Video Stream", isVideo = true)
            }
        }
        // Audio elements and their source children.
        doc.select("audio").forEach { audio ->
            val direct = audio.absUrl("src").ifBlank { audio.attr("src") }
                .ifBlank { audio.absUrl("data-src").ifBlank { audio.attr("data-src") } }
            if (direct.isNotBlank()) {
                addCandidate(direct, "Audio", isAudio = true, allowExtensionlessMedia = true, mimeHint = audio.attr("type"))
            }
            audio.select("source").forEach { source ->
                val sourceUrl = source.absUrl("src").ifBlank { source.attr("src") }
                    .ifBlank { source.absUrl("data-src").ifBlank { source.attr("data-src") } }
                if (sourceUrl.isNotBlank()) {
                    addCandidate(
                        sourceUrl,
                        "Audio",
                        isAudio = true,
                        allowExtensionlessMedia = true,
                        mimeHint = source.attr("type")
                    )
                }
            }
        }

        // Direct video anchor links or data-video containers
        doc.select("a[href*='.mp4'], a[href*='.webm'], a[href*='.m3u8'], [data-video-url]").forEach { el ->
            val vUrl = el.absUrl("data-video-url").ifBlank { el.attr("data-video-url") }
                .ifBlank { el.absUrl("href").ifBlank { el.attr("href") } }
            if (vUrl.isNotBlank()) {
                val label = el.text().trim().takeIf { it.isNotBlank() && it.length <= 40 } ?: "Video"
                addCandidate(vUrl, label, isVideo = true)
            }
        }

        // 5. <picture> elements and source tags
        doc.select("picture").forEach { pic ->
            pic.select("source").forEach { s ->
                val sSrcset = s.attr("srcset").ifBlank { s.attr("data-srcset") }
                val selected = selectBestSrcsetUrl(sSrcset)
                    ?: s.attr("src").ifBlank { s.attr("data-src") }
                if (selected.isNotBlank()) {
                    addCandidate(
                        selected,
                        "Picture Source",
                        allowExtensionlessMedia = true,
                        declaredWidth = s.attr("width").toIntOrNull(),
                        declaredHeight = s.attr("height").toIntOrNull()
                    )
                }
            }
            pic.select("img").forEach { img ->
                processImageElement(img, "Picture Image")
            }
        }

        // 6. Content Images (articles, main content, figures, galleries, Next.js pictures, body images)
        val imageElements = doc.select("article img, main img, #content img, .content img, .post-content img, .entry-content img, figure img, .gallery img, img")
        for (img in imageElements) {
            processImageElement(img, defaultTitle)
            if (items.size >= 80) break
        }

        // 7. <noscript> tags (extracts images and media rendered for non-JS / SSR)
        doc.select("noscript").forEach { ns ->
            val nsHtml = ns.html()
            if (nsHtml.contains("<img", ignoreCase = true) || nsHtml.contains("<picture", ignoreCase = true) || nsHtml.contains("<video", ignoreCase = true)) {
                runCatching {
                    val nsDoc = Jsoup.parseBodyFragment(nsHtml, baseUrl)
                    nsDoc.select("img").forEach { processImageElement(it, "Noscript Image") }
                    nsDoc.select("video").forEach { vid ->
                        val vSrc = vid.absUrl("src").ifBlank { vid.attr("src") }.ifBlank { vid.attr("data-src") }
                        if (vSrc.isNotBlank()) addCandidate(vSrc, "Noscript Video", isVideo = true)
                        vid.select("source").forEach { s ->
                            val sSrc = s.absUrl("src").ifBlank { s.attr("src") }.ifBlank { s.attr("data-src") }
                            if (sSrc.isNotBlank()) addCandidate(sSrc, "Noscript Video Stream", isVideo = true)
                        }
                    }
                }
            }
        }

        // 8. Do not regex-scrape arbitrary script bundles. They contain unrelated page
        // assets (logos, ads, widget thumbnails); the primary JSON-LD image is handled
        // through jsonLdImageUrl and hydrated media is discovered from the live DOM.
        
        // 9. Pull background-image URLs from inline styles and <style> stylesheets
        val cssUrlRegex = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
        doc.select("main [style*='url('], article [style*='url('], figure [style*='url('], [role='img'][style*='url(']").forEach { el ->
            cssUrlRegex.findAll(el.attr("style")).forEach { m ->
                val bgUrl = m.groupValues[1].trim()
                if (!bgUrl.startsWith("data:", ignoreCase = true)) {
                    addCandidate(bgUrl, "Background Image")
                }
            }
        }

        // 10. Guarantee at least one valid media item if preview URL is available
        if (items.isEmpty() && primaryPreviewUrl.isNotBlank()) {
            addCandidate(primaryPreviewUrl, defaultTitle)
        }

        return MediaNormalizer.deduplicateMediaInboxItems(items, baseUrl)
    }

    /**
     * Expands an HLS .m3u8 playlist to discover multi-bitrate/resolution variants.
     */
    suspend fun expandM3u8Playlist(m3u8Url: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val res = OkHttpDownloader.getHtml(m3u8Url, maxBytes = 512 * 1024L)
            if (res.isSuccessful && !res.bodyString.isNullOrBlank() && res.bodyString.contains("#EXTM3U")) {
                val variants = mutableListOf<String>()
                val lines = res.bodyString.lines()
                var isStreamInf = false
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                        isStreamInf = true
                    } else if (isStreamInf && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val resolved = resolveAbsoluteUrl(res.finalUrl.ifBlank { m3u8Url }, trimmed)
                        if (resolved.isNotBlank()) variants.add(resolved)
                        isStreamInf = false
                    }
                }
                if (variants.isNotEmpty()) return@withContext variants
            }
        } catch (_: Exception) {}
        listOf(m3u8Url)
    }

    private fun extractAbsUrl(element: org.jsoup.nodes.Element, attributeKey: String): String {
        val abs = element.absUrl(attributeKey).trim()
        if (abs.isNotBlank()) return Parser.unescapeEntities(abs, false)
        val attr = element.attr(attributeKey).trim()
        return Parser.unescapeEntities(attr, false)
    }

    private fun cleanText(text: String): String {
        if (text.isBlank()) return ""
        val unescaped = Parser.unescapeEntities(text, false)
        return unescaped
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun resolveAbsoluteUrl(baseUrl: String, candidate: String): String {
        if (candidate.isBlank()) return ""
        val unescaped = Parser.unescapeEntities(candidate.trim(), false).trim()
        val trimmed = unescaped
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(trimmed).toString()
        } catch (_: Exception) {
            if (trimmed.startsWith("/")) {
                val host = try { URI(baseUrl).host } catch (_: Exception) { "" }
                if (!host.isNullOrBlank()) "https://$host$trimmed" else trimmed
            } else {
                trimmed
            }
        }
    }
}
