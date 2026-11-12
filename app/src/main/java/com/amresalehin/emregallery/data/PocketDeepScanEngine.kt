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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-demand, hardware-accelerated Deep Scan engine for JavaScript-heavy, scrollable websites.
 * Operates strictly upon user request with a hard 30-second cap.
 * Streams discovered media incrementally back to the caller in real time.
 */
object PocketDeepScanEngine {
    private const val TAG = "PocketDeepScanEngine"
    private const val MAX_SCAN_DURATION_MS = 30_000L
    private const val SCROLL_CADENCE_MS = 1_000L
    private const val EARLY_EXIT_CONSECUTIVE_EMPTY = 4

    private val isScanning = AtomicBoolean(false)
    private var scanJob: Job? = null
    private var activeWebViewRef: WeakReference<WebView>? = null
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
     * Starts an on-demand hardware-accelerated deep scan for the given bookmark URL.
     * Capped strictly at 30 seconds or until manually stopped.
     */
    fun startScan(
        context: Context,
        bookmarkId: String,
        targetUrl: String,
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

        isScanning.set(true)
        currentBookmarkId = bookmarkId

        scanJob = scope.launch(Dispatchers.Main) {
            val appContext = context.applicationContext
            var webView: WebView? = null
            val seenUrls = mutableSetOf<String>()
            var consecutiveEmptyCount = 0
            val startTime = System.currentTimeMillis()

            try {
                webView = createHardwareAcceleratedWebView(appContext, targetUrl)
                activeWebViewRef = WeakReference(webView)

                // Load target page
                webView.loadUrl(targetUrl)

                // Allow initial DOM load and hydrate
                delay(1_500L)

                while (isActive && isScanning.get()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= MAX_SCAN_DURATION_MS) {
                        Log.d(TAG, "Deep scan reached 30s maximum duration for $targetUrl. Auto-stopping.")
                        break
                    }

                    val currentWv = webView ?: break

                    // Active 30% RAM protection: enforce memory limit to prevent app freezes & renderer crashes
                    if (RamGovernor.isHeapLimitExceeded()) {
                        Log.w(TAG, "RAM limit of 30% reached during deep scan. Enforcing memory reclaim.")
                        RamGovernor.enforceLimit(appContext, currentWv)
                        delay(150L)
                        if (RamGovernor.isHeapLimitExceeded()) {
                            Log.w(TAG, "RAM usage still >= 30% after reclaim. Halting deep scan to protect system stability.")
                            break
                        }
                    }

                    // Trigger scroll on the page and prune heavy media buffers in off-screen DOM
                    val scrollScript = """
                        (function() {
                            try {
                                var scrollAmt = Math.max(Math.floor(window.innerHeight * 0.85), 500);
                                window.scrollBy(0, scrollAmt);
                            } catch(e) {}
                        })();
                    """.trimIndent()
                    currentWv.evaluateJavascript(scrollScript, null)

                    // Prune video and canvas buffers in Chromium renderer to prevent code -1 crashes
                    currentWv.evaluateJavascript(RamGovernor.MEDIA_BUFFER_PRUNING_JS, null)

                    // Execute media extraction script in page context
                    val extractionScript = buildMediaExtractionScript()
                    val rawResult = evaluateJavascriptSuspend(currentWv, extractionScript)

                    val discoveredBatch = parseExtractedMediaJson(rawResult, targetUrl)
                    val newItems = discoveredBatch.filter { item ->
                        seenUrls.add(item.sourceUrl)
                    }

                    if (newItems.isNotEmpty()) {
                        consecutiveEmptyCount = 0
                        withContext(Dispatchers.Default) {
                            onIncrementalMediaDiscovered(newItems)
                        }
                    } else {
                        consecutiveEmptyCount++
                        // If 4 consecutive scrolls after at least 8 seconds yield zero new media, exit early
                        if (elapsed >= 8_000L && consecutiveEmptyCount >= EARLY_EXIT_CONSECUTIVE_EMPTY) {
                            Log.d(TAG, "Early exit triggered: No new media after $consecutiveEmptyCount scrolls.")
                            break
                        }
                    }

                    delay(SCROLL_CADENCE_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Deep scan encountered an exception for $targetUrl", e)
            } finally {
                withContext(Dispatchers.Main) {
                    destroyWebView(webView)
                    activeWebViewRef = null
                }
                isScanning.set(false)
                currentBookmarkId = null
                withContext(Dispatchers.Default) {
                    onCompleted()
                }
            }
        }
    }

    /**
     * Instantly stops an ongoing deep scan, releasing the WebView immediately.
     */
    fun stopScan() {
        if (!isScanning.getAndSet(false)) {
            return
        }
        currentBookmarkId = null
        scanJob?.cancel()
        scanJob = null

        Handler(Looper.getMainLooper()).post {
            val wv = activeWebViewRef?.get()
            if (wv != null) {
                destroyWebView(wv)
            }
            activeWebViewRef = null
        }
    }

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
                    stopScan()
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

    private fun buildMediaExtractionScript(): String {
        return """
            (function() {
                var found = [];
                var seen = {};

                function add(url, hint, isVideo) {
                    if (!url || typeof url !== 'string') return;
                    url = url.trim();
                    if (!url || url.indexOf('http') !== 0 || url.indexOf('data:') === 0) return;
                    if (url.indexOf('1x1') !== -1 || url.indexOf('pixel') !== -1 || url.indexOf('spacer') !== -1) return;
                    if (seen[url]) return;
                    seen[url] = true;
                    found.push({
                        url: url,
                        title: hint || '',
                        isVideo: isVideo === true
                    });
                }

                // 1. All images in DOM
                var imgs = document.getElementsByTagName('img');
                for (var i = 0; i < imgs.length; i++) {
                    var el = imgs[i];
                    var src = el.currentSrc || el.src;
                    var lazy = el.getAttribute('data-src') || el.getAttribute('data-original') ||
                               el.getAttribute('data-lazy-src') || el.getAttribute('data-large-file') ||
                               el.getAttribute('data-hi-res-src');
                    var alt = el.getAttribute('alt') || el.getAttribute('title') || '';
                    if (lazy) add(lazy, alt, false);
                    if (src) add(src, alt, false);

                    // Parse srcset
                    var ss = el.getAttribute('srcset') || el.getAttribute('data-srcset');
                    if (ss) {
                        var parts = ss.split(',');
                        for (var p = 0; p < parts.length; p++) {
                            var u = parts[p].trim().split(/\s+/)[0];
                            if (u) add(u, alt, false);
                        }
                    }
                }

                // 2. Picture source elements
                var sources = document.querySelectorAll('picture source');
                for (var s = 0; s < sources.length; s++) {
                    var pSrcset = sources[s].getAttribute('srcset') || sources[s].getAttribute('data-srcset');
                    if (pSrcset) {
                        var sparts = pSrcset.split(',');
                        for (var sp = 0; sp < sparts.length; sp++) {
                            var su = sparts[sp].trim().split(/\s+/)[0];
                            if (su) add(su, 'Picture Source', false);
                        }
                    }
                }

                // 3. Videos and video sources
                var vids = document.getElementsByTagName('video');
                for (var v = 0; v < vids.length; v++) {
                    var vEl = vids[v];
                    var poster = vEl.getAttribute('poster');
                    if (poster) add(poster, 'Video Poster', false);
                    var vSrc = vEl.currentSrc || vEl.src || vEl.getAttribute('data-src') || vEl.getAttribute('data-url');
                    if (vSrc) add(vSrc, 'Video', true);
                    var vs = vEl.getElementsByTagName('source');
                    for (var vsIdx = 0; vsIdx < vs.length; vsIdx++) {
                        var vsSrc = vs[vsIdx].src || vs[vsIdx].getAttribute('data-src') || vs[vsIdx].getAttribute('data-url');
                        if (vsSrc) add(vsSrc, 'Video Stream', true);
                    }
                }

                // Audio elements
                var audios = document.getElementsByTagName('audio');
                for (var a = 0; a < audios.length; a++) {
                    var aEl = audios[a];
                    var aSrc = aEl.currentSrc || aEl.src || aEl.getAttribute('data-src');
                    if (aSrc) add(aSrc, 'Audio', false);
                    var asrcs = aEl.getElementsByTagName('source');
                    for (var asIdx = 0; asIdx < asrcs.length; asIdx++) {
                        var asSrc = asrcs[asIdx].src || asrcs[asIdx].getAttribute('data-src');
                        if (asSrc) add(asSrc, 'Audio Stream', false);
                    }
                }

                // 4. Background images in visible styles
                var styledEls = document.querySelectorAll('[style*="background"], [style*="url("]');
                for (var b = 0; b < styledEls.length; b++) {
                    var styleStr = styledEls[b].getAttribute('style') || '';
                    var match = styleStr.match(/url\(\s*['"]?([^'")]+)['"]?\s*\)/i);
                    if (match && match[1]) {
                        add(match[1], 'Background Asset', false);
                    }
                }

                // 5. OpenGraph & Twitter Card media elements
                var metas = document.querySelectorAll('meta[property*="image"], meta[name*="image"], meta[property*="video"], meta[name*="video"]');
                for (var m = 0; m < metas.length; m++) {
                    var mContent = metas[m].getAttribute('content');
                    var mProp = (metas[m].getAttribute('property') || metas[m].getAttribute('name') || '').toLowerCase();
                    var isV = mProp.indexOf('video') !== -1;
                    if (mContent) add(mContent, 'Page Media', isV);
                }

                // 6. Direct media anchor links
                var links = document.querySelectorAll('a[href]');
                for (var l = 0; l < links.length; l++) {
                    var href = links[l].getAttribute('href');
                    if (href && href.match(/\.(jpe?g|png|webp|avif|gif|bmp|svg|mp4|webm|mov|m3u8|mpd|mp3|m4a|aac|flac)(\?.*)?$/i)) {
                        var isVidLink = !!href.match(/\.(mp4|webm|mov|m3u8|mpd)(\?.*)?$/i);
                        add(href, links[l].innerText || 'Linked Media', isVidLink);
                    }
                }

                // 7. Performance resource entries for recently fetched media
                try {
                    if (window.performance && typeof window.performance.getEntriesByType === 'function') {
                        var resources = window.performance.getEntriesByType('resource');
                        for (var r = 0; r < resources.length; r++) {
                            var rUrl = resources[r].name;
                            if (rUrl && rUrl.match(/\.(jpe?g|png|webp|avif|gif|mp4|webm|m3u8|mpd|mp3|m4a|aac)(\?.*)?$/i)) {
                                var isV = !!rUrl.match(/\.(mp4|webm|m3u8|mpd)(\?.*)?$/i);
                                add(rUrl, 'Resource Stream', isV);
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

                val titleHint = obj.optString("title", "").ifBlank {
                    resolvedUrl.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() && it.length > 2 } ?: "Discovered Media"
                }
                val isVideo = obj.optBoolean("isVideo", false)
                val isStream = resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains(".mpd", ignoreCase = true)
                val isAudio = resolvedUrl.contains(".mp3", ignoreCase = true) || resolvedUrl.contains(".aac", ignoreCase = true) ||
                        resolvedUrl.contains(".ogg", ignoreCase = true) || resolvedUrl.contains(".wav", ignoreCase = true) ||
                        resolvedUrl.contains(".m4a", ignoreCase = true)
                val format = when {
                    isStream -> "M3U8"
                    resolvedUrl.contains(".webm", ignoreCase = true) -> "WEBM"
                    isVideo || resolvedUrl.contains(".mp4", ignoreCase = true) -> "MP4"
                    isAudio -> "AUDIO"
                    resolvedUrl.contains(".png", ignoreCase = true) -> "PNG"
                    resolvedUrl.contains(".gif", ignoreCase = true) -> "GIF"
                    resolvedUrl.contains(".webp", ignoreCase = true) -> "WEBP"
                    resolvedUrl.contains(".svg", ignoreCase = true) -> "SVG"
                    resolvedUrl.contains(".avif", ignoreCase = true) -> "AVIF"
                    else -> "JPEG"
                }

                items.add(
                    MediaInboxItem(
                        id = "deep_${System.currentTimeMillis()}_${resolvedUrl.hashCode().toUInt().toString(16)}",
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
        return items
    }
}
