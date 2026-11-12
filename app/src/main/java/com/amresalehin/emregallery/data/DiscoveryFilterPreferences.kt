package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Filter configuration state for the Media Discovery page.
 *
 * Requirements:
 * - Minimum Width default: 480 px
 * - Minimum Height default: 540 px
 * - Minimum Size default: 10 KB (10240 bytes)
 * - SVG and PNG discovery disabled by default.
 * - All other supported media types enabled by default.
 * - Updates apply immediately in memory with debounced background disk persistence.
 */
data class DiscoveryFilterState(
    val minWidth: Int = 480,
    val minHeight: Int = 540,
    val minSizeBytes: Long = 10 * 1024L,
    val enabledCategories: Set<String> = setOf("ALL", "VIDEOS", "STREAMS", "IMAGES", "AUDIO"),
    val enabledFormats: Set<String> = DEFAULT_ENABLED_FORMATS
) {
    companion object {
        val IMAGE_FORMATS = listOf("JPG", "WEBP", "PNG", "SVG", "GIF", "AVIF", "BMP")
        val VIDEO_FORMATS = listOf("MP4", "WEBM", "MOV", "MKV")
        val STREAM_FORMATS = listOf("M3U8", "MPD")
        val AUDIO_FORMATS = listOf("MP3", "M4A", "AAC", "WAV", "FLAC", "OPUS")

        val ALL_FORMATS: List<String> = IMAGE_FORMATS + VIDEO_FORMATS + STREAM_FORMATS + AUDIO_FORMATS

        // PNG and SVG disabled by default as required
        val DEFAULT_ENABLED_FORMATS: Set<String> = ALL_FORMATS.filter {
            it != "PNG" && it != "SVG"
        }.toSet()
    }
}

/**
 * Singleton repository/manager for persistent discovery filters.
 * Synchronous in-memory updates ensure 0-lag immediate UI recomposition.
 */
object DiscoveryFilterManager {

    private const val PREFS_NAME = "discovery_filter_prefs"
    private const val KEY_MIN_WIDTH = "min_width"
    private const val KEY_MIN_HEIGHT = "min_height"
    private const val KEY_MIN_SIZE = "min_size_bytes"
    private const val KEY_FORMATS = "enabled_formats"
    private const val KEY_CATEGORIES = "enabled_categories"

    private var prefs: SharedPreferences? = null
    private val _filterState = MutableStateFlow(DiscoveryFilterState())
    val filterState: StateFlow<DiscoveryFilterState> = _filterState.asStateFlow()

    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingPersistJob: Job? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
        val minW = sp.getInt(KEY_MIN_WIDTH, 480)
        val minH = sp.getInt(KEY_MIN_HEIGHT, 540)
        val minSize = sp.getLong(KEY_MIN_SIZE, 10 * 1024L)

        val formats = sp.getStringSet(KEY_FORMATS, null) ?: DiscoveryFilterState.DEFAULT_ENABLED_FORMATS
        val categories = sp.getStringSet(KEY_CATEGORIES, null) ?: setOf("ALL", "VIDEOS", "STREAMS", "IMAGES", "AUDIO")

        _filterState.value = DiscoveryFilterState(
            minWidth = minW,
            minHeight = minH,
            minSizeBytes = minSize,
            enabledCategories = categories,
            enabledFormats = formats
        )
    }

    /**
     * Updates state immediately in memory for instant 0-latency UI updates,
     * while debouncing disk persistence to prevent main thread frame drops.
     */
    fun updateFilters(newState: DiscoveryFilterState) {
        _filterState.value = newState
        pendingPersistJob?.cancel()
        pendingPersistJob = persistScope.launch {
            delay(150)
            prefs?.edit()?.apply {
                putInt(KEY_MIN_WIDTH, newState.minWidth)
                putInt(KEY_MIN_HEIGHT, newState.minHeight)
                putLong(KEY_MIN_SIZE, newState.minSizeBytes)
                putStringSet(KEY_FORMATS, newState.enabledFormats)
                putStringSet(KEY_CATEGORIES, newState.enabledCategories)
                apply()
            }
        }
    }

    fun resetToDefaults() {
        val defaultState = DiscoveryFilterState()
        updateFilters(defaultState)
    }

    /**
     * Normalizes the format of a ScrapedMediaItem into canonical token e.g. JPG, PNG, SVG, MP4, etc.
     */
    fun normalizeFormat(item: ScrapedMediaItem): String {
        val fmt = item.format.uppercase(Locale.ROOT)
        val urlLower = item.url.lowercase(Locale.ROOT)
        val mimeLower = item.mimeType.lowercase(Locale.ROOT)

        return when {
            fmt.contains("SVG") || urlLower.contains(".svg") || mimeLower.contains("svg") -> "SVG"
            fmt.contains("PNG") || urlLower.contains(".png") || mimeLower.contains("png") -> "PNG"
            fmt.contains("WEBP") || urlLower.contains(".webp") || mimeLower.contains("webp") -> "WEBP"
            fmt.contains("GIF") || urlLower.contains(".gif") || mimeLower.contains("gif") -> "GIF"
            fmt.contains("AVIF") || urlLower.contains(".avif") || mimeLower.contains("avif") -> "AVIF"
            fmt.contains("BMP") || urlLower.contains(".bmp") || mimeLower.contains("bmp") -> "BMP"
            fmt.contains("JPG") || fmt.contains("JPEG") || urlLower.contains(".jpg") || urlLower.contains(".jpeg") || mimeLower.contains("jpeg") -> "JPG"

            fmt.contains("M3U8") || fmt.contains("HLS") || urlLower.contains(".m3u8") || mimeLower.contains("mpegurl") -> "M3U8"
            fmt.contains("MPD") || fmt.contains("DASH") || urlLower.contains(".mpd") || mimeLower.contains("dash") -> "MPD"
            fmt.contains("MP4") || urlLower.contains(".mp4") || mimeLower.contains("video/mp4") -> "MP4"
            fmt.contains("WEBM") || urlLower.contains(".webm") || mimeLower.contains("video/webm") -> "WEBM"
            fmt.contains("MOV") || urlLower.contains(".mov") || mimeLower.contains("quicktime") -> "MOV"
            fmt.contains("MKV") || urlLower.contains(".mkv") || mimeLower.contains("matroska") -> "MKV"

            fmt.contains("MP3") || urlLower.contains(".mp3") || mimeLower.contains("audio/mpeg") || mimeLower.contains("audio/mp3") -> "MP3"
            fmt.contains("M4A") || urlLower.contains(".m4a") -> "M4A"
            fmt.contains("AAC") || urlLower.contains(".aac") || mimeLower.contains("aac") -> "AAC"
            fmt.contains("WAV") || urlLower.contains(".wav") || mimeLower.contains("wav") -> "WAV"
            fmt.contains("FLAC") || urlLower.contains(".flac") || mimeLower.contains("flac") -> "FLAC"
            fmt.contains("OPUS") || urlLower.contains(".opus") || mimeLower.contains("opus") -> "OPUS"

            item.isStream -> "M3U8"
            item.isVideo -> "MP4"
            item.isAudio -> "MP3"
            else -> "JPG"
        }
    }

    /**
     * Evaluates whether a ScrapedMediaItem satisfies all configured filter criteria.
     * Follows Pocket screen reference behavior:
     * - Immediate filter application
     * - Dimension filtering applies to images where dimensions are known
     * - Videos/streams/audio are not restricted by width/height dimension filters
     */
    fun matchesFilters(item: ScrapedMediaItem, filter: DiscoveryFilterState): Boolean {
        // 1. Format check
        val format = normalizeFormat(item)
        if (!filter.enabledFormats.contains(format)) {
            return false
        }

        // 2. Category check (Category switches)
        val matchesCategory = when {
            item.isStream -> filter.enabledCategories.contains("STREAMS") || filter.enabledCategories.contains("ALL")
            item.isVideo -> filter.enabledCategories.contains("VIDEOS") || filter.enabledCategories.contains("ALL")
            item.isAudio -> filter.enabledCategories.contains("AUDIO") || filter.enabledCategories.contains("ALL")
            else -> filter.enabledCategories.contains("IMAGES") || filter.enabledCategories.contains("ALL")
        }
        if (!matchesCategory) return false

        // 3. Dimensions check (Pocket screen reference: videos/streams/audio are not restricted by width/height)
        if (filter.minWidth > 0 || filter.minHeight > 0) {
            if (!item.isVideo && !item.isStream && !item.isAudio) {
                val w = item.width ?: MediaCaptureEngine.extractDimension(item.resolutionHint, item.url, isWidth = true)
                val h = item.height ?: MediaCaptureEngine.extractDimension(item.resolutionHint, item.url, isWidth = false)
                if (w != null && filter.minWidth > 0 && w < filter.minWidth) {
                    return false
                }
                if (h != null && filter.minHeight > 0 && h < filter.minHeight) {
                    return false
                }
            }
        }

        // 4. Minimum media size check (Pocket screen reference)
        if (filter.minSizeBytes > 0) {
            val sizeBytes = item.sizeBytes ?: MediaClassifier.estimateDataUriSizeBytes(item.url)
            if (sizeBytes != null && sizeBytes < filter.minSizeBytes) {
                return false
            }
        }

        return true
    }
}
