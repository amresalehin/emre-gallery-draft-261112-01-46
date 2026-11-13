package com.amresalehin.emregallery.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Concurrent, bounded discovery dedicated to Pocket URL intake.
 * Each discovery engine reports into one merge registry; success from one engine
 * never short-circuits the others.
 */
data class PocketDiscoveryResult(
    val metadata: LinkMetadata,
    val items: List<ScrapedMediaItem>,
    val timedOut: Boolean,
    val errors: List<String>
)

object PocketDiscoveryCoordinator {
    const val MAX_DISCOVERY_DURATION_MS = 30_000L
    private const val STATIC_HTTP_TIMEOUT_MS = 8_000L
    private const val EXTRACTOR_TIMEOUT_SECONDS = 10

    fun isDirectMediaUrl(url: String): Boolean = ExtractionCoordinator.isDirectMediaUrl(url)

    suspend fun discover(
        context: Context,
        rawUrl: String,
        onStatus: (String) -> Unit = {}
    ): PocketDiscoveryResult = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val normalizedUrl = if (
            !trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) "https://$trimmed" else trimmed
        val domain = MediaClassifier.cleanDomainFromUrl(normalizedUrl)

        // Direct files skip webpage extractors and hidden WebView creation.
        if (isDirectMediaUrl(normalizedUrl)) {
            val classification = MediaClassifier.classify(normalizedUrl)
            val name = normalizedUrl.substringAfterLast('/').substringBefore('?').ifBlank { domain }
            val directItem = ScrapedMediaItem(
                id = "pocket_direct_${MediaClassifier.deduplicationKey(normalizedUrl).hashCode().toUInt()}",
                url = normalizedUrl,
                title = name,
                isVideo = classification.isVideo,
                isAudio = classification.isAudio,
                isStream = classification.isStream,
                format = classification.format,
                mimeType = classification.mimeType,
                source = "Pocket Direct",
                provenance = MediaProvenance("Pocket Direct", normalizedUrl),
                downloadHeaders = mapOf(
                    "Referer" to normalizedUrl,
                    "User-Agent" to OkHttpDownloader.DEFAULT_USER_AGENT
                )
            )
            return@withContext PocketDiscoveryResult(
                metadata = LinkMetadata(url = normalizedUrl, title = name, domain = domain),
                items = listOf(directItem),
                timedOut = false,
                errors = emptyList()
            )
        }

        val cookieString = runCatching {
            OkHttpDownloader.sharedCookieJar.getCookieString(normalizedUrl)
        }.getOrDefault("").takeIf { it.isNotBlank() }
        val extractorOptions = GalleryDlOptions(
            cookies = cookieString,
            timeoutSeconds = EXTRACTOR_TIMEOUT_SECONDS
        )

        val registryLock = Any()
        val candidates = LinkedHashMap<String, ScrapedMediaItem>()
        val errors = ConcurrentLinkedQueue<String>()
        var pageMetadata: LinkMetadata? = null
        var bestTitle = ""

        fun mergeCandidates(incoming: List<ScrapedMediaItem>) {
            if (incoming.isEmpty()) return
            synchronized(registryLock) {
                MediaNormalizer.mergeCandidates(candidates, incoming)
                if (bestTitle.isBlank()) {
                    bestTitle = incoming.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
                }
            }
        }

        fun mergeMetadata(incoming: LinkMetadata) {
            synchronized(registryLock) {
                val current = pageMetadata
                pageMetadata = if (current == null) incoming else current.copy(
                    title = current.title.ifBlank { incoming.title },
                    description = current.description.ifBlank { incoming.description },
                    previewImageUrl = current.previewImageUrl.ifBlank { incoming.previewImageUrl },
                    siteName = current.siteName.ifBlank { incoming.siteName },
                    domain = current.domain.ifBlank { incoming.domain },
                    faviconUrl = current.faviconUrl.ifBlank { incoming.faviconUrl }
                )
            }
        }

        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = mutableListOf<Job>()

        // Static HTML / DOM extraction.
        jobs += engineScope.launch {
            try {
                onStatus("Scanning static HTML and page metadata")
                val metadata = LinkMetadataFetcher.fetch(
                    normalizedUrl,
                    includeSpecializedExtractor = false,
                    networkTimeoutMillis = STATIC_HTTP_TIMEOUT_MS,
                    allowHttpFallbacks = false
                )
                mergeMetadata(metadata)
                val staticItems = metadata.mediaItems.mapNotNull { item ->
                    if (item.sourceUrl.isBlank() ||
                        item.sourceUrl.contains("google.com/s2/favicons", ignoreCase = true)
                    ) return@mapNotNull null
                    val classification = MediaClassifier.classify(item.sourceUrl, item.format)
                    val isAudio = item.isAudio || classification.isAudio
                    val isStream = item.isStream || classification.isStream
                    val isVideo = !isAudio && (item.isVideo || classification.isVideo || isStream)
                    ScrapedMediaItem(
                        id = item.id,
                        url = item.sourceUrl,
                        title = item.title,
                        isVideo = isVideo,
                        isAudio = isAudio,
                        isStream = isStream,
                        format = item.format.ifBlank { classification.format },
                        thumbnailUrl = if (isVideo || isAudio) null else item.sourceUrl,
                        source = "Static HTML",
                        mimeType = classification.mimeType,
                        sizeBytes = item.sizeBytes.takeIf { it > 0L },
                        provenance = MediaProvenance("Static HTML", normalizedUrl),
                        metadata = mapOf("source_url" to normalizedUrl),
                        downloadHeaders = mapOf(
                            "Referer" to normalizedUrl,
                            "User-Agent" to OkHttpDownloader.DEFAULT_USER_AGENT
                        )
                    )
                }
                mergeCandidates(staticItems)
            } catch (e: Exception) {
                errors.add("Static HTML: ${e.message ?: e.javaClass.simpleName}")
                Log.w("PocketDiscovery", "Static HTML extraction failed", e)
            }
        }

        // yt-dlp.
        jobs += engineScope.launch {
            try {
                if (!GalleryDlEngine.isYtDlpAvailable()) return@launch
                onStatus("Extracting with yt-dlp")
                val result = GalleryDlEngine.extractYtDlp(normalizedUrl, extractorOptions)
                if (result.success) mergeCandidates(result.items)
                if (result.pageTitle.isNotBlank()) synchronized(registryLock) {
                    if (bestTitle.isBlank()) bestTitle = result.pageTitle
                }
                result.errorMessage?.let { errors.add("yt-dlp: $it") }
            } catch (e: Exception) {
                errors.add("yt-dlp: ${e.message ?: e.javaClass.simpleName}")
                Log.w("PocketDiscovery", "yt-dlp extraction failed", e)
            }
        }

        // gallery-dl runs independently, not as yt-dlp fallback.
        jobs += engineScope.launch {
            try {
                if (!GalleryDlEngine.isGalleryDlAvailable()) return@launch
                onStatus("Extracting with gallery-dl")
                val result = GalleryDlEngine.extractGalleryDl(normalizedUrl, extractorOptions)
                if (result.success) mergeCandidates(result.items)
                if (result.pageTitle.isNotBlank()) synchronized(registryLock) {
                    if (bestTitle.isBlank()) bestTitle = result.pageTitle
                }
                result.errorMessage?.let { errors.add("gallery-dl: $it") }
            } catch (e: Exception) {
                errors.add("gallery-dl: ${e.message ?: e.javaClass.simpleName}")
                Log.w("PocketDiscovery", "gallery-dl extraction failed", e)
            }
        }

        // One browser session captures dynamic DOM and intercepted media.
        jobs += engineScope.launch(Dispatchers.Main) {
            try {
                onStatus("Inspecting dynamic page media")
                val browserResult = BrowserSession.extractHeadless(
                    context = context,
                    targetUrl = normalizedUrl,
                    onStatusUpdate = onStatus,
                    onMediaDiscovered = { item -> mergeCandidates(listOf(item)) },
                    publishToDownloader = false
                )
                if (browserResult.items.isNotEmpty()) mergeCandidates(browserResult.items)
                browserResult.errorMessage?.let { errors.add("Browser: $it") }
            } catch (e: Exception) {
                errors.add("Browser: ${e.message ?: e.javaClass.simpleName}")
                Log.w("PocketDiscovery", "Browser extraction failed", e)
            }
        }

        onStatus("Running all discovery engines concurrently")
        val completed = try {
            withTimeoutOrNull(MAX_DISCOVERY_DURATION_MS) {
                jobs.joinAll()
                true
            } ?: false
        } catch (e: Exception) {
            errors.add("Coordinator: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            jobs.forEach { it.cancel() }
            engineScope.cancel()
        }

        val finalItems = synchronized(registryLock) {
            MediaClassifier.deduplicateScrapedItems(candidates.values.toList())
        }
        val capturedMetadata = synchronized(registryLock) { pageMetadata }
        val fallbackTitle = synchronized(registryLock) { bestTitle }.ifBlank { domain }
        val metadata = (capturedMetadata ?: LinkMetadata(url = normalizedUrl)).copy(
            title = capturedMetadata?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
            domain = capturedMetadata?.domain?.ifBlank { domain } ?: domain,
            mediaItems = finalItems.map { item ->
                MediaInboxItem(
                    id = item.id,
                    title = item.title,
                    localUri = "",
                    sourceUrl = item.url,
                    isVideo = item.isVideo,
                    isAudio = item.isAudio,
                    isStream = item.isStream,
                    format = item.format,
                    sizeBytes = item.sizeBytes ?: 0L,
                    thumbnailUrl = item.thumbnailUrl.orEmpty()
                )
            }
        )

        if (!completed) {
            errors.add("Discovery deadline reached after 30 seconds")
            onStatus("Discovery time limit reached; processing discovered media")
        } else {
            onStatus("Discovery completed; processing unique media")
        }

        PocketDiscoveryResult(
            metadata = metadata,
            items = finalItems,
            timedOut = !completed,
            errors = errors.toList()
        )
    }
}
