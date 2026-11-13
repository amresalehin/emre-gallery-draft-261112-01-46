package com.amresalehin.emregallery.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * On-demand, hardware-accelerated Deep Scan engine for JavaScript-heavy, scrollable websites.
 * Streams newly accepted media incrementally, then completes at a stable page bottom or
 * a bounded safety limit.
 */
object PocketDeepScanEngine {
    private const val TAG = "PocketDeepScanEngine"
    private const val SCROLL_CADENCE_MS = 900L
    private const val SCROLL_SETTLE_MS = 600L
    private const val MAX_SCAN_DURATION_MS = 120_000L
    private const val MAX_SCROLL_STEPS = 120
    private const val BOTTOM_STABLE_CYCLES = 4

    private val isScanning = AtomicBoolean(false)
    private val scanGeneration = AtomicLong(0L)
    private var scanJob: Job? = null
    private var activeWebViewRef: WeakReference<WebView>? = null
    private var activeCompletionScope: CoroutineScope? = null
    private var activeCompletionCallback: (() -> Unit)? = null
    private var currentBookmarkId: String? = null

    fun isScanning(bookmarkId: String? = null): Boolean {
        return if (bookmarkId == null) {
            isScanning.get()
        } else {
            isScanning.get() && currentBookmarkId == bookmarkId
        }
    }

    fun getActiveBookmarkId(): String? = if (isScanning.get()) currentBookmarkId else null

    /**
     * Starts an on-demand deep scan. Existing bookmark items seed the discovery registry,
     * preventing already captured/downloaded media from being emitted again.
     */
    fun startScan(
        context: Context,
        bookmarkId: String,
        targetUrl: String,
        existingItems: List<MediaInboxItem> = emptyList(),
        maxScanDurationMs: Long = MAX_SCAN_DURATION_MS,
        scope: CoroutineScope,
        onIncrementalMediaDiscovered: (List<MediaInboxItem>) -> Unit,
        onCompleted: () -> Unit
    ) {
        // Stop any previous active scan before initiating a new one
        stopScan()

        if (targetUrl.isBlank()) {
            onCompleted()
            return
        }

        val generation = scanGeneration.incrementAndGet()
        activeCompletionScope = scope
        activeCompletionCallback = onCompleted
        isScanning.set(true)
        currentBookmarkId = bookmarkId

        scanJob = scope.launch(Dispatchers.Main) {
            val appContext = context.applicationContext
            var webView: WebView? = null
            val bestSeenByKey = mutableMapOf<String, MediaInboxItem>()
            existingItems.forEach { existing ->
                val key = MediaNormalizer.canonicalKey(existing.sourceUrl.ifBlank { existing.localUri })
                if (key.isNotBlank()) {
                    val previous = bestSeenByKey[key]
                    if (previous == null || MediaNormalizer.compareInboxItemQuality(existing, previous) > 0) {
                        bestSeenByKey[key] = existing
                    }
                }
            }
            val existingSourceUrls = existingItems.map { it.sourceUrl.trim() }
                .filter { it.startsWith("http", ignoreCase = true) }
                .distinct()
            val extractionScript = buildMediaExtractionScript(existingSourceUrls)
            val scanStartedAt = System.currentTimeMillis()
            var scrollSteps = 0
            var bottomStableCycles = 0
            var previousScrollHeight = -1

            try {
                webView = createHardwareAcceleratedWebView(appContext, targetUrl)
                activeWebViewRef = WeakReference(webView)

                // Load target page
                webView.loadUrl(targetUrl)

                // Allow initial DOM load and hydrate
                delay(1_500L)

                // Scan the current viewport, scroll through the page, then stop at a stable bottom.
                while (isActive && isScanning.get()) {
                    val currentWv = webView ?: break
                    if (scrollSteps >= MAX_SCROLL_STEPS ||
                        System.currentTimeMillis() - scanStartedAt >= maxScanDurationMs
                    ) {
                        Log.i(TAG, "Deep scan reached its safety ceiling after $scrollSteps scroll steps.")
                        break
                    }

                    // Active 30% RAM protection: enforce memory limit to prevent app freezes & renderer crashes.
                    if (RamGovernor.isHeapLimitExceeded()) {
                        Log.w(TAG, "RAM limit of 30% reached during deep scan. Enforcing memory reclaim.")
                        RamGovernor.enforceLimit(appContext, currentWv)
                        delay(150L)
                        if (RamGovernor.isHeapLimitExceeded()) {
                            Log.w(TAG, "RAM usage still >= 30% after reclaim. Halting deep scan to protect system stability.")
                            break
                        }
                    }

                    // Move the main page or its largest scrollable content container.
                    val scrollStateRaw = evaluateJavascriptSuspend(currentWv, buildScrollScript())
                    val scrollState = parseScrollMetrics(scrollStateRaw)
                    scrollSteps++
                    delay(SCROLL_SETTLE_MS)

                    // Prune off-screen video/canvas buffers, then inspect the hydrated DOM.
                    currentWv.evaluateJavascript(RamGovernor.MEDIA_BUFFER_PRUNING_JS, null)
                    val rawResult = evaluateJavascriptSuspend(currentWv, extractionScript)
                    val discoveredBatch = parseExtractedMediaJson(rawResult, targetUrl)
                    val newItems = discoveredBatch.filter { item ->
                        val key = MediaNormalizer.canonicalKey(item.sourceUrl.ifBlank { item.localUri })
                        if (key.isBlank()) {
                            false
                        } else {
                            val previous = bestSeenByKey[key]
                            if (previous == null || MediaNormalizer.compareInboxItemQuality(item, previous) > 0) {
                                bestSeenByKey[key] = item
                                true
                            } else {
                                false
                            }
                        }
                    }

                    if (newItems.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            onIncrementalMediaDiscovered(newItems)
                        }
                    }

                    val heightStable = scrollState != null &&
                        (previousScrollHeight < 0 || scrollState.scrollHeight <= previousScrollHeight + 2)
                    val idleAtBottom = scrollState?.atBottom == true &&
                        newItems.isEmpty() && heightStable
                    val unableToMove = (scrollState == null || !scrollState.moved) && newItems.isEmpty()
                    if (idleAtBottom || unableToMove) {
                        bottomStableCycles++
                    } else {
                        bottomStableCycles = 0
                    }
                    previousScrollHeight = scrollState?.scrollHeight ?: previousScrollHeight

                    if (bottomStableCycles >= BOTTOM_STABLE_CYCLES) {
                        Log.i(TAG, "Deep scan completed after reaching a stable page bottom ($scrollSteps scroll steps).")
                        break
                    }
                    delay(SCROLL_CADENCE_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Deep scan encountered an exception for $targetUrl", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    destroyWebView(webView)
                    if (scanGeneration.get() == generation) {
                        activeWebViewRef = null
                    }
                }
                // A stale cancelled scan must never clear the state or callback of a newer scan.
                if (scanGeneration.get() == generation) {
                    isScanning.set(false)
                    currentBookmarkId = null
                    val completion = activeCompletionCallback ?: onCompleted
                    activeCompletionCallback = null
                    activeCompletionScope = null
                    withContext(NonCancellable + Dispatchers.Default) {
                        completion()
                    }
                }
            }
        }
    }

    /**
     * Instantly stops an ongoing deep scan, releasing the WebView immediately.
     */
    fun stopScan(notifyCompletion: Boolean = false) {
        scanGeneration.incrementAndGet()
        isScanning.set(false)
        currentBookmarkId = null

        // Detach the old WebView before posting destruction so a newly-started scan
        // cannot have its WebView accidentally destroyed by this queued cleanup.
        val viewToDestroy = activeWebViewRef?.get()
        activeWebViewRef = null
        scanJob?.cancel()
        scanJob = null

        val completion = if (notifyCompletion) activeCompletionCallback else null
        val completionScope = activeCompletionScope
        activeCompletionCallback = null
        activeCompletionScope = null

        if (viewToDestroy != null) {
            Handler(Looper.getMainLooper()).post {
                destroyWebView(viewToDestroy)
            }
        }
        if (completion != null) {
            completionScope?.launch(Dispatchers.Default) {
                runCatching { completion() }
            }
        }
    }

    private data class ScrollMetrics(
        val atBottom: Boolean,
        val moved: Boolean,
        val scrollHeight: Int
    )

    private fun parseScrollMetrics(rawResult: String): ScrollMetrics? {
        if (rawResult.isBlank() || rawResult == "null") return null
        return runCatching {
            val json = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                org.json.JSONTokener(rawResult).nextValue().toString()
            } else rawResult
            val obj = JSONObject(json)
            ScrollMetrics(
                atBottom = obj.optBoolean("atBottom", false),
                moved = obj.optBoolean("moved", false),
                scrollHeight = obj.optInt("scrollHeight", 0)
            )
        }.getOrNull()
    }

    private fun buildScrollScript(): String = """
        (function() {
            try {
                var root = document.scrollingElement || document.documentElement || document.body;
                var target = root;
                var rootRange = Math.max(0, (root ? root.scrollHeight : 0) - (root ? root.clientHeight : 0));
                if (rootRange < 32) {
                    var candidates = document.querySelectorAll('body *');
                    var bestRange = 0;
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        if (!el || el.clientHeight < 120 || el.clientWidth < window.innerWidth * 0.45) continue;
                        var style = window.getComputedStyle(el);
                        if (!style || (style.overflowY !== 'auto' && style.overflowY !== 'scroll')) continue;
                        var range = el.scrollHeight - el.clientHeight;
                        if (range > bestRange + 32) {
                            bestRange = range;
                            target = el;
                        }
                    }
                }
                var before = target ? (target.scrollTop || 0) : 0;
                var viewport = target ? target.clientHeight : window.innerHeight;
                var amount = Math.max(Math.floor(viewport * 0.82), 420);
                if (target) {
                    // Assign scrollTop directly so pages with CSS smooth scrolling do not
                    // report "not moved" before their animated scroll begins.
                    target.scrollTop = before + amount;
                }
                var top = target ? (target.scrollTop || 0) : 0;
                var height = target ? target.scrollHeight : 0;
                var client = target ? target.clientHeight : 0;
                return JSON.stringify({
                    atBottom: top + client >= height - 24,
                    moved: top > before + 1,
                    scrollHeight: height
                });
            } catch (e) {
                return JSON.stringify({atBottom: false, moved: false, scrollHeight: 0});
            }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createHardwareAcceleratedWebView(context: Context, initialUrl: String): WebView {
        return WebView(context).apply {
            // Software layer for background DOM scraping avoids missing GPU rendernode crashes in emulators
            layoutParams = ViewGroup.LayoutParams(1080, 1920)
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            runCatching {
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1080, 1920)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString()?.lowercase(Locale.ROOT) ?: return null
                    // Block intrusive ad trackers or audio streams, but allow images, videos, and scripts
                    if (url.contains("google-analytics") || url.contains("doubleclick") ||
                        url.contains("facebook.com/tr") || url.contains("adservice")) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return null
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    Log.w(TAG, "Deep scan WebView renderer process gone (didCrash=${detail?.didCrash()}). Cleanly terminating scan.")
                    stopScan(notifyCompletion = true)
                    return true
                }
            }
        }
    }

    private fun destroyWebView(wv: WebView?) {
        if (wv == null) return
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.onPause()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up deep scan WebView", e)
        }
    }

    private suspend fun evaluateJavascriptSuspend(wv: WebView, script: String): String =
        withContext(Dispatchers.Main) {
            kotlin.coroutines.suspendCoroutine { cont ->
                wv.evaluateJavascript(script) { result ->
                    cont.resumeWith(Result.success(result ?: ""))
                }
            }
        }

    private fun buildMediaExtractionScript(existingSourceUrls: List<String> = emptyList()): String {
        val existingUrlsJson = JSONArray(existingSourceUrls).toString()
        return """
            (function() {
                var found = [];
                var existingUrls = $existingUrlsJson;
                var seen = window.__emrePocketDeepScanSeen || Object.create(null);
                for (var seedIndex = 0; seedIndex < existingUrls.length; seedIndex++) {
                    seen[existingUrls[seedIndex]] = true;
                }
                window.__emrePocketDeepScanSeen = seen;
                var imageFingerprints = window.__emrePocketDeepScanImageFingerprints || new WeakMap();
                var videoFingerprints = window.__emrePocketDeepScanVideoFingerprints || new WeakMap();
                var audioFingerprints = window.__emrePocketDeepScanAudioFingerprints || new WeakMap();
                window.__emrePocketDeepScanImageFingerprints = imageFingerprints;
                window.__emrePocketDeepScanVideoFingerprints = videoFingerprints;
                window.__emrePocketDeepScanAudioFingerprints = audioFingerprints;

                function isUiAsset(url) {
                    var value = String(url || '').toLowerCase().split('?')[0].split('#')[0];
                    var name = value.substring(value.lastIndexOf('/') + 1);
                    return value.indexOf('favicon') !== -1 ||
                        value.indexOf('apple-touch-icon') !== -1 ||
                        /^(logo|icon|avatar|badge|emoji|spinner|placeholder|sprite)([-_.@]|$)/.test(name);
                }

                function add(url, hint, isVideo, isAudio, explicitMedia, width, height, mime) {
                    if (!url || typeof url !== 'string') return;
                    url = url.trim();
                    if (!/^https?:\/\//i.test(url) || /(?:1x1|pixel|spacer|tracking|beacon)/i.test(url)) return;
                    if (isUiAsset(url)) return;
                    if ((width > 0 && width < 96) || (height > 0 && height < 96)) return;
                    if (Object.prototype.hasOwnProperty.call(seen, url)) return;
                    seen[url] = true;
                    found.push({
                        url: url,
                        title: hint || '',
                        isVideo: isVideo === true,
                        isAudio: isAudio === true,
                        explicitMedia: explicitMedia === true,
                        width: width || 0,
                        height: height || 0,
                        mime: mime || ''
                    });
                }

                // 1. Real image elements. Keep one best source per element; do not add
                // every srcset resolution as a separate item.
                var imgs = document.getElementsByTagName('img');
                for (var i = 0; i < imgs.length; i++) {
                    var el = imgs[i];
                    var src = el.currentSrc || el.src;
                    var fullResolution = el.getAttribute('data-hi-res-src') || el.getAttribute('data-large-file') ||
                                         el.getAttribute('data-original') || el.getAttribute('data-zoom-src') ||
                                         el.getAttribute('data-full-src');
                    var lazyFallback = el.getAttribute('data-src') || el.getAttribute('data-lazy-src') ||
                                       el.getAttribute('data-url');
                    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {width: 0, height: 0};
                    var width = el.naturalWidth || parseInt(el.getAttribute('width') || '0', 10) || Math.round(rect.width || 0);
                    var height = el.naturalHeight || parseInt(el.getAttribute('height') || '0', 10) || Math.round(rect.height || 0);
                    var ss = el.getAttribute('srcset') || el.getAttribute('data-srcset');
                    var fingerprint = [src, fullResolution, lazyFallback, ss, width, height].join('|');
                    if (imageFingerprints.get(el) === fingerprint) continue;
                    imageFingerprints.set(el, fingerprint);
                    var classAndId = ((el.className && String(el.className)) || '') + ' ' + (el.id || '');
                    if (/(^|[\s_-])(logo|icon|avatar|emoji|badge|spinner|placeholder|sprite)([\s_-]|$)/i.test(classAndId)) continue;
                    var selectedUrl = fullResolution || '';
                    if (!selectedUrl && ss) {
                        var parts = ss.split(',');
                        var bestScore = -1;
                        for (var p = 0; p < parts.length; p++) {
                            var tokens = parts[p].trim().split(/\s+/);
                            var candidateUrl = tokens[0];
                            var descriptor = tokens.length > 1 ? tokens[tokens.length - 1] : '';
                            var widthMatch = descriptor.match(/^(\d+(?:\.\d+)?)w$/i);
                            var densityMatch = descriptor.match(/^(\d+(?:\.\d+)?)x$/i);
                            var candidateScore = widthMatch ? parseFloat(widthMatch[1]) :
                                (densityMatch ? parseFloat(densityMatch[1]) * 1000 : 0);
                            if (candidateUrl && candidateScore >= bestScore) {
                                selectedUrl = candidateUrl;
                                bestScore = candidateScore;
                            }
                        }
                    }
                    if (!selectedUrl) selectedUrl = lazyFallback || src;
                    if (selectedUrl) add(selectedUrl, el.getAttribute('alt') || el.getAttribute('title') || '',
                        false, false, true, width, height, 'image/*');
                }

                // 2. Video and audio elements. Posters remain previews, not independent media.
                var vids = document.getElementsByTagName('video');
                for (var v = 0; v < vids.length; v++) {
                    var vEl = vids[v];
                    var vSrc = vEl.currentSrc || vEl.src || vEl.getAttribute('data-src') || vEl.getAttribute('data-url');
                    var vMime = vEl.getAttribute('type') || '';
                    if (!vSrc) {
                        var vs = vEl.getElementsByTagName('source');
                        for (var vsIdx = 0; vsIdx < vs.length; vsIdx++) {
                            var candidate = vs[vsIdx].src || vs[vsIdx].getAttribute('data-src') || vs[vsIdx].getAttribute('data-url');
                            if (candidate) { vSrc = candidate; vMime = vs[vsIdx].getAttribute('type') || ''; break; }
                        }
                    }
                    var videoFingerprint = [vSrc || '', vMime || ''].join('|');
                    if (videoFingerprints.get(vEl) !== videoFingerprint) {
                        videoFingerprints.set(vEl, videoFingerprint);
                        if (vSrc) add(vSrc, 'Video', true, false, true, 0, 0, vMime);
                    }
                }

                var audios = document.getElementsByTagName('audio');
                for (var a = 0; a < audios.length; a++) {
                    var aEl = audios[a];
                    var aSrc = aEl.currentSrc || aEl.src || aEl.getAttribute('data-src');
                    var aMime = aEl.getAttribute('type') || '';
                    if (!aSrc) {
                        var asrcs = aEl.getElementsByTagName('source');
                        for (var asIdx = 0; asIdx < asrcs.length; asIdx++) {
                            var aCandidate = asrcs[asIdx].src || asrcs[asIdx].getAttribute('data-src');
                            if (aCandidate) { aSrc = aCandidate; aMime = asrcs[asIdx].getAttribute('type') || ''; break; }
                        }
                    }
                    var audioFingerprint = [aSrc || '', aMime || ''].join('|');
                    if (audioFingerprints.get(aEl) !== audioFingerprint) {
                        audioFingerprints.set(aEl, audioFingerprint);
                        if (aSrc) add(aSrc, 'Audio', false, true, true, 0, 0, aMime);
                    }
                }

                // 3. Content backgrounds only, never every decorative element on the page.
                var styledEls = document.querySelectorAll('main [style*="background"], article [style*="background"], figure [style*="background"], [role="img"][style*="background"]');
                for (var b = 0; b < styledEls.length; b++) {
                    var styleStr = styledEls[b].getAttribute('style') || '';
                    var match = styleStr.match(/url\(\s*['"]?([^'")]+)['"]?\s*\)/i);
                    var box = styledEls[b].getBoundingClientRect ? styledEls[b].getBoundingClientRect() : {width: 0, height: 0};
                    if (match && match[1] && box.width >= 240 && box.height >= 160) {
                        add(match[1], 'Content Background', false, false, false, box.width, box.height, '');
                    }
                }

                // 4. Explicit page-level media metadata; unknown-format URLs still need
                // the Kotlin classifier's URL / MIME validation.
                var metas = document.querySelectorAll('meta[property="og:image"], meta[property="og:image:url"], meta[property="og:image:secure_url"], meta[name="twitter:image"], meta[name="twitter:image:src"], meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                for (var m = 0; m < metas.length; m++) {
                    var mContent = metas[m].getAttribute('content');
                    var mProp = (metas[m].getAttribute('property') || metas[m].getAttribute('name') || '').toLowerCase();
                    var isV = mProp.indexOf('video') !== -1;
                    if (mContent) add(mContent, 'Page Media', isV, false, true, 0, 0, isV ? 'video/*' : 'image/*');
                }

                // 5. Direct media links only. A webpage / API link is not a media item.
                var links = document.querySelectorAll('a[href]');
                for (var l = 0; l < links.length; l++) {
                    var href = links[l].getAttribute('href');
                    if (href && href.match(/\.(jpe?g|png|webp|avif|gif|bmp|svg|mp4|webm|mov|m3u8|mpd|mp3|m4a|aac|flac)(\?.*)?$/i)) {
                        var isVidLink = !!href.match(/\.(mp4|webm|mov|m3u8|mpd)(\?.*)?$/i);
                        var isAudioLink = !!href.match(/\.(mp3|m4a|aac|flac)(\?.*)?$/i);
                        add(href, links[l].innerText || 'Linked Media', isVidLink, isAudioLink, false, 0, 0, '');
                    }
                }

                // 6. Performance entries are retained only for direct video/audio streams;
                // image resources are already represented by actual image elements.
                try {
                    if (window.performance && typeof window.performance.getEntriesByType === 'function') {
                        var resources = window.performance.getEntriesByType('resource');
                        for (var r = 0; r < resources.length; r++) {
                            var rUrl = resources[r].name;
                            if (rUrl && rUrl.match(/\.(mp4|webm|mov|m3u8|mpd|mp3|m4a|aac|flac)(\?.*)?$/i)) {
                                var isV = !!rUrl.match(/\.(mp4|webm|mov|m3u8|mpd)(\?.*)?$/i);
                                var isA = !!rUrl.match(/\.(mp3|m4a|aac|flac)(\?.*)?$/i);
                                add(rUrl, 'Media Stream', isV, isA, false, 0, 0, '');
                            }
                        }
                    }
                } catch(e) {}

                return JSON.stringify(found);
            })();
        """.trimIndent()
    }

    private fun parseExtractedMediaJson(rawJson: String, baseUrl: String): List<MediaInboxItem> {
        if (rawJson.isBlank() || rawJson == "null" || rawJson == "\"\"") return emptyList()

        val jsonString = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
            try {
                org.json.JSONTokener(rawJson).nextValue().toString()
            } catch (_: Exception) {
                rawJson
            }
        } else {
            rawJson
        }

        val items = mutableListOf<MediaInboxItem>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val rawUrl = obj.optString("url", "").trim()
                if (rawUrl.isBlank()) continue

                val resolvedUrl = LinkMetadataFetcher.resolveAbsoluteUrl(baseUrl, rawUrl)
                if (resolvedUrl.isBlank() || !resolvedUrl.startsWith("http", ignoreCase = true)) continue
                if (MediaClassifier.isMediaFragment(resolvedUrl)) continue

                val titleHint = obj.optString("title", "").ifBlank {
                    resolvedUrl.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() && it.length > 2 } ?: "Discovered Media"
                }
                val mimeHint = obj.optString("mime", "").takeIf { it.isNotBlank() }
                val explicitMedia = obj.optBoolean("explicitMedia", false)
                if (!MediaClassifier.isLikelyMediaCandidate(
                        resolvedUrl,
                        mimeHint = mimeHint,
                        allowExtensionlessMedia = explicitMedia
                    )
                ) continue

                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)
                if ((width in 1..95) || (height in 1..95)) continue

                val classification = MediaClassifier.classify(resolvedUrl, mimeHint)
                val isAudioHint = titleHint.startsWith("Audio", ignoreCase = true)
                val isAudio = obj.optBoolean("isAudio", false) || classification.isAudio || isAudioHint
                val isStream = classification.isStream ||
                    resolvedUrl.contains(".m3u8", ignoreCase = true) ||
                    resolvedUrl.contains(".mpd", ignoreCase = true)
                // Explicit audio MIME/path always wins over the generic video-element flag.
                val isVideo = !isAudio && (obj.optBoolean("isVideo", false) || classification.isVideo || isStream)
                val format = when {
                    isAudio && classification.isAudio -> classification.format
                    isAudio -> "AUDIO"
                    isStream && resolvedUrl.contains(".mpd", ignoreCase = true) -> "MPD"
                    isStream -> "M3U8"
                    isVideo && classification.format.isNotBlank() && classification.format != "JPEG" -> classification.format
                    isVideo && resolvedUrl.contains(".webm", ignoreCase = true) -> "WEBM"
                    isVideo -> "MP4"
                    else -> classification.format
                }

                items.add(
                    MediaInboxItem(
                        id = "deep_${MediaNormalizer.canonicalKey(resolvedUrl).hashCode().toUInt().toString(16)}",
                        title = titleHint,
                        localUri = "",
                        sourceUrl = resolvedUrl,
                        isVideo = isVideo || isStream,
                        isAudio = isAudio,
                        isStream = isStream,
                        format = format,
                        dateAddedMillis = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing deep scan media JSON", e)
        }
        return MediaNormalizer.deduplicateMediaInboxItems(items, baseUrl)
    }
}
