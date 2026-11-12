package com.amresalehin.emregallery.data

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import coil.Coil

/**
 * Actively monitors and enforces the 30% RAM limit to prevent app freezes,
 * OutOfMemory crashes, and Chromium renderer termination (code -1) on pages
 * with infinite scroll or heavy media buffers.
 */
object RamGovernor {
    private const val TAG = "RamGovernor"

    /**
     * Strict 30% RAM threshold.
     */
    const val MAX_RAM_FRACTION = 0.30

    /**
     * Checks if current JVM heap usage has reached or exceeded the 30% RAM threshold.
     */
    fun isHeapLimitExceeded(): Boolean {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        if (max <= 0L) return false
        val fraction = used.toDouble() / max.toDouble()
        return fraction >= MAX_RAM_FRACTION
    }

    /**
     * Returns the current JVM heap usage fraction (0.0 to 1.0).
     */
    fun getHeapUsageFraction(): Double {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        if (max <= 0L) return 0.0
        return used.toDouble() / max.toDouble()
    }

    /**
     * Enforces the 30% RAM ceiling by clearing Coil in-memory bitmap caches,
     * dropping temporary decoders, clearing WebView RAM buffers, and triggering GC.
     */
    fun enforceLimit(context: Context? = null, webView: WebView? = null) {
        if (!isHeapLimitExceeded()) return

        val fraction = getHeapUsageFraction()
        Log.w(TAG, "Heap usage is ${(fraction * 100).toInt()}% (exceeds 30% RAM limit). Enforcing memory reclaim.")

        // 1. Evict in-memory Coil bitmap caches
        context?.let { ctx ->
            try {
                Coil.imageLoader(ctx).memoryCache?.clear()
            } catch (t: Throwable) {
                Log.w(TAG, "Error clearing Coil cache", t)
            }
        }

        // 2. Clear face thumbnail caches
        try {
            com.amresalehin.emregallery.face.FaceThumbnailHelper.clearCache()
        } catch (_: Throwable) {}

        // 3. Clear Chromium in-memory RAM cache (without deleting persistent local storage/cookies)
        try {
            webView?.clearCache(false)
        } catch (_: Throwable) {}

        // 4. Force JVM garbage collection
        System.gc()
    }

    /**
     * JavaScript snippet that prunes heavy media buffers (videos, audio, large offscreen canvases)
     * during infinite scroll or deep scanning to prevent Chromium renderer OOM crashes (code -1).
     */
    const val MEDIA_BUFFER_PRUNING_JS: String = """
        (function() {
            try {
                // 1. Pause any active video/audio and disable preloading
                var mediaEls = document.querySelectorAll('video, audio');
                for (var i = 0; i < mediaEls.length; i++) {
                    var m = mediaEls[i];
                    if (!m.paused) { m.pause(); }
                    m.preload = 'none';
                    
                    // Prune offscreen videos far outside viewport (> 1500px away)
                    var rect = m.getBoundingClientRect();
                    if (rect.bottom < -1500 || rect.top > (window.innerHeight + 1500)) {
                        if (m.src && !m.dataset.prunedSrc) {
                            m.dataset.prunedSrc = m.src;
                            m.removeAttribute('src');
                            m.load();
                        }
                    }
                }

                // 2. Clear heavy off-screen canvas contexts
                var canvases = document.querySelectorAll('canvas');
                if (canvases.length > 5) {
                    for (var c = 0; c < canvases.length; c++) {
                        var can = canvases[c];
                        var cr = can.getBoundingClientRect();
                        if (cr.bottom < -2000) {
                            can.width = 1;
                            can.height = 1;
                        }
                    }
                }
            } catch(e) {}
        })();
    """
}
