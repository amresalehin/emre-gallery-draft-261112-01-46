package com.amresalehin.emregallery.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.amresalehin.emregallery.ffmpeg.FfmpegKitEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

data class GalleryDlStatus(
    val available: Boolean,
    val version: String,
    val engineName: String,
    val supportedExtractorsCount: Int,
    val popularSites: List<GalleryDlSite>
)

data class GalleryDlSite(
    val name: String,
    val domains: List<String>,
    val icon: String
)

data class GalleryDlSupportInfo(
    val isSupported: Boolean,
    val category: String,
    val subcategory: String,
    val extractorName: String,
    val displayName: String,
    val extractorEngine: String = "yt-dlp"
)

data class GalleryDlOptions(
    val userAgent: String? = null,
    val cookies: String? = null,
    val timeoutSeconds: Int = 30
)

data class GalleryDlExtractResult(
    val success: Boolean,
    val extractorName: String = "",
    val category: String = "",
    val pageTitle: String = "",
    val pageDescription: String = "",
    val previewImageUrl: String = "",
    val items: List<ScrapedMediaItem> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Gallery-dl and yt-dlp integration service powered by Chaquopy Python SDK on Android.
 * yt-dlp is the primary engine; gallery-dl serves as secondary for galleries and image hosts.
 */
object GalleryDlEngine {
    private const val TAG = "GalleryDlEngine"
    @Volatile
    private var isInitialized = false
    private val initLock = Any()

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(initLock) {
            if (isInitialized) return
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                isInitialized = true
                Log.d(TAG, "Chaquopy Python SDK and yt-dlp/gallery-dl runtime initialized successfully")
            } catch (t: Throwable) {
                Log.w(TAG, "Python runtime initialization deferred or failed: ${t.message}")
            }
        }
    }

    fun isPythonStarted(): Boolean {
        return try {
            Python.isStarted()
        } catch (_: Throwable) {
            false
        }
    }

    fun isYtDlpAvailable(): Boolean {
        if (!isPythonStarted()) return false
        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            runner.callAttr("is_ytdlp_available").toBoolean()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed checking yt-dlp availability", e)
            false
        }
    }

    fun isGalleryDlAvailable(): Boolean {
        if (!isPythonStarted()) return false
        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            runner.callAttr("is_gallery_dl_available").toBoolean()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed checking gallery-dl availability", e)
            false
        }
    }

    fun isAvailable(): Boolean {
        return isPythonStarted() && (isYtDlpAvailable() || isGalleryDlAvailable())
    }

    /**
     * Retrieves runtime status, version, and supported popular sites.
     */
    fun getStatus(): GalleryDlStatus {
        if (!isAvailable()) {
            return GalleryDlStatus(
                available = false,
                version = "Unavailable",
                engineName = "yt-dlp (Primary) / gallery-dl (Secondary)",
                supportedExtractorsCount = 0,
                popularSites = defaultPopularSites()
            )
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val statusJson = runner.callAttr("get_engine_status").toString()
            val json = JSONObject(statusJson)

            val sitesList = mutableListOf<GalleryDlSite>()
            val popularArr = json.optJSONArray("popular_extractors") ?: JSONArray()
            for (i in 0 until popularArr.length()) {
                val obj = popularArr.getJSONObject(i)
                val doms = mutableListOf<String>()
                val dArr = obj.optJSONArray("domains") ?: JSONArray()
                for (j in 0 until dArr.length()) {
                    doms.add(dArr.getString(j))
                }
                sitesList.add(
                    GalleryDlSite(
                        name = obj.optString("name", "Unknown"),
                        domains = doms,
                        icon = obj.optString("icon", "public")
                    )
                )
            }

            GalleryDlStatus(
                available = json.optBoolean("available", true),
                version = json.optString("version", "1.32.19"),
                engineName = json.optString("engine", "yt-dlp (Primary) / gallery-dl (Secondary)"),
                supportedExtractorsCount = json.optInt("supported_categories_count", 287),
                popularSites = if (sitesList.isNotEmpty()) sitesList else defaultPopularSites()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get gallery-dl status", e)
            GalleryDlStatus(
                available = true,
                version = "1.32.19",
                engineName = "yt-dlp (Primary) / gallery-dl (Secondary)",
                supportedExtractorsCount = 287,
                popularSites = defaultPopularSites()
            )
        }
    }

    /**
     * Checks whether the target URL matches a site-specific yt-dlp or gallery-dl extractor.
     */
    fun checkSupport(url: String): GalleryDlSupportInfo {
        if (url.isBlank()) {
            return GalleryDlSupportInfo(false, "", "", "", "", "yt-dlp")
        }

        if (!isAvailable()) {
            val lower = url.lowercase()
            for (site in defaultPopularSites()) {
                if (site.domains.any { lower.contains(it) }) {
                    return GalleryDlSupportInfo(
                        isSupported = true,
                        category = site.name.lowercase(),
                        subcategory = "",
                        extractorName = "${site.name}Extractor",
                        displayName = site.name,
                        extractorEngine = "gallery-dl"
                    )
                }
            }
            return GalleryDlSupportInfo(false, "", "", "", "", "yt-dlp")
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val checkJson = runner.callAttr("check_support", url.trim()).toString()
            val json = JSONObject(checkJson)
            GalleryDlSupportInfo(
                isSupported = json.optBoolean("supported", false),
                category = json.optString("category", ""),
                subcategory = json.optString("subcategory", ""),
                extractorName = json.optString("extractor", ""),
                displayName = json.optString("display_name", json.optString("category", "")),
                extractorEngine = json.optString("engine", "yt-dlp")
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkSupport error", e)
            GalleryDlSupportInfo(false, "", "", "", "", "yt-dlp")
        }
    }

    /**
     * Specifically checks whether gallery-dl supports this URL.
     */
    fun checkGalleryDlSupport(url: String): GalleryDlSupportInfo {
        if (url.isBlank()) {
            return GalleryDlSupportInfo(false, "", "", "", "", "gallery-dl")
        }

        if (!isAvailable()) {
            val lower = url.lowercase()
            for (site in defaultPopularSites()) {
                if (site.domains.any { lower.contains(it) }) {
                    return GalleryDlSupportInfo(
                        isSupported = true,
                        category = site.name.lowercase(),
                        subcategory = "",
                        extractorName = "${site.name}Extractor",
                        displayName = site.name,
                        extractorEngine = "gallery-dl"
                    )
                }
            }
            return GalleryDlSupportInfo(false, "", "", "", "", "gallery-dl")
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val checkJson = runner.callAttr("check_gallery_dl_support", url.trim()).toString()
            val json = JSONObject(checkJson)
            GalleryDlSupportInfo(
                isSupported = json.optBoolean("supported", false),
                category = json.optString("category", ""),
                subcategory = json.optString("subcategory", ""),
                extractorName = json.optString("extractor", ""),
                displayName = json.optString("display_name", json.optString("category", "")),
                extractorEngine = "gallery-dl"
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkGalleryDlSupport error", e)
            GalleryDlSupportInfo(false, "", "", "", "", "gallery-dl")
        }
    }

    private fun parseExtractedItems(root: JSONObject, pageUrl: String): Pair<String, List<ScrapedMediaItem>> {
        val pageTitle = root.optString("pageTitle", "")
        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val itemsList = mutableListOf<ScrapedMediaItem>()

        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            val catStr = obj.optString("category", "IMAGES")
            val mediaCategory = when (catStr.uppercase()) {
                "VIDEOS" -> MediaCategory.VIDEOS
                "AUDIO" -> MediaCategory.AUDIO
                "STREAMS" -> MediaCategory.STREAMS
                else -> MediaCategory.IMAGES
            }

            val isVideo = obj.optBoolean("isVideo", false)
            val isAudio = obj.optBoolean("isAudio", false)
            val isStream = obj.optBoolean("isStream", false)

            val item = ScrapedMediaItem(
                id = obj.optString("id", "dl_${System.currentTimeMillis()}_$i"),
                url = obj.getString("url"),
                title = obj.optString("title", "media_${i + 1}"),
                isVideo = isVideo,
                isAudio = isAudio,
                isStream = isStream,
                category = mediaCategory,
                format = obj.optString("format", "IMAGE"),
                mimeType = obj.optString("mimeType", if (isVideo) "video/mp4" else "image/jpeg"),
                thumbnailUrl = obj.optString("thumbnailUrl").takeIf { !it.isNullOrBlank() },
                source = obj.optString("source", "yt-dlp"),
                resolutionHint = obj.optString("resolutionHint").takeIf { !it.isNullOrBlank() },
                sizeBytes = if (obj.has("sizeBytes") && !obj.isNull("sizeBytes")) obj.getLong("sizeBytes") else null,
                provenance = MediaProvenance(
                    extractorEngine = obj.optString("source", "yt-dlp / gallery-dl"),
                    pageUrl = pageUrl
                ),
                metadata = mapOf("source_url" to pageUrl),
                downloadHeaders = mapOf(
                    "User-Agent" to OkHttpDownloader.DEFAULT_USER_AGENT,
                    "Referer" to pageUrl
                )
            )
            itemsList.add(item)
        }
        return Pair(pageTitle, itemsList)
    }

    private suspend fun executePythonExtractor(
        methodName: String,
        url: String,
        options: GalleryDlOptions
    ): GalleryDlExtractResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            var waitCount = 0
            while (!isAvailable() && waitCount < 10) {
                kotlinx.coroutines.delay(100L)
                waitCount++
            }
        }
        if (!isAvailable()) {
            return@withContext GalleryDlExtractResult(
                success = false,
                errorMessage = "Chaquopy Python runtime is not active."
            )
        }

        try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")

            val effectiveUa = options.userAgent ?: BotDetectionMitigator.STEALTH_USER_AGENT
            val effectiveCookies = options.cookies
                ?: runCatching { android.webkit.CookieManager.getInstance().getCookie(url.trim()) }.getOrNull()

            val optsJson = JSONObject().apply {
                put("user_agent", effectiveUa)
                effectiveCookies?.let { put("cookies", it) }
                put("timeout", options.timeoutSeconds)
            }.toString()

            val rawResult = runner.callAttr(methodName, url.trim(), optsJson).toString()
            val root = JSONObject(rawResult)

            val success = root.optBoolean("success", false)
            if (!success) {
                val err = root.optString("error", "Extraction failed or produced no items")
                return@withContext GalleryDlExtractResult(
                    success = false,
                    errorMessage = err
                )
            }

            val extractorName = root.optString("extractor", root.optString("engine", methodName))
            val (pageTitle, itemsList) = parseExtractedItems(root, url.trim())
            val pageDescription = root.optString("pageDescription", "")
            val previewImageUrl = root.optString("previewImageUrl", "")

            GalleryDlExtractResult(
                success = true,
                extractorName = extractorName,
                pageTitle = pageTitle,
                pageDescription = pageDescription,
                previewImageUrl = previewImageUrl,
                items = itemsList
            )
        } catch (e: Exception) {
            Log.e(TAG, "$methodName execution failed", e)
            GalleryDlExtractResult(
                success = false,
                errorMessage = e.localizedMessage ?: "$methodName execution failed"
            )
        }
    }

    suspend fun extractGalleryDl(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_gallery_dl", url, options)

    suspend fun extractYtDlp(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_yt_dlp", url, options)

    suspend fun extractMedia(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_media", url, options)

    /**
     * Downloads a media file exclusively using yt-dlp (primary) and gallery-dl (secondary fallback).
     */
    suspend fun downloadMedia(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        cookies: String? = null,
        domain: String? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            val effectiveDomain = domain?.replace(Regex("[^a-zA-Z0-9.-]"), "_")?.trim()?.takeIf { it.isNotBlank() }
                ?: OkHttpDownloader.extractDomain(referer ?: mediaUrl)
            val dir = OkHttpDownloader.getTargetStorageDir(context, effectiveDomain)

            val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(dir, "${System.currentTimeMillis()}_$sanitizedName")

            val userAgent = headers?.get("User-Agent") ?: BotDetectionMitigator.STEALTH_USER_AGENT
            val effectiveCookies = cookies ?: headers?.get("Cookie")
                ?: runCatching {
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.getCookie(mediaUrl) ?: referer?.let { cm.getCookie(it) }
                }.getOrNull()
            val headersJson = if (!headers.isNullOrEmpty()) {
                val json = JSONObject()
                headers.forEach { (k, v) -> json.put(k, v) }
                json.toString()
            } else ""

            val mediaPath = mediaUrl.substringBefore('?').substringBefore('#').lowercase()
            val isClearlyImage = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp", ".svg", ".heic", ".heif")
                .any { mediaPath.endsWith(it) } ||
                listOf("format=jpg", "format=jpeg", "format=png", "format=webp", "format=avif")
                    .any { mediaUrl.contains(it, ignoreCase = true) }
            val ytDlpCanHandle = !isClearlyImage && (
                checkSupport(mediaUrl).extractorEngine == "yt-dlp" ||
                    (!referer.isNullOrBlank() && checkSupport(referer).extractorEngine == "yt-dlp")
            )
            if (isYtDlpAvailable() && ytDlpCanHandle) {
                val nativeAttempt = downloadWithNativeYtDlp(
                    context = context,
                    mediaUrl = mediaUrl,
                    fileName = sanitizedName,
                    referer = referer,
                    userAgent = userAgent,
                    cookies = effectiveCookies ?: "",
                    headers = headers ?: emptyMap(),
                    domain = effectiveDomain
                )
                if (nativeAttempt.handled) {
                    return@withContext nativeAttempt.file
                }
            }

            if (isAvailable()) {
                val py = Python.getInstance()
                val runner = py.getModule("gallery_dl_runner")
                val success = runner.callAttr(
                    "download_media_file",
                    mediaUrl,
                    targetFile.absolutePath,
                    referer ?: "",
                    userAgent,
                    effectiveCookies ?: "",
                    headersJson
                ).toBoolean()

                if (success && targetFile.exists() && targetFile.length() > 0L) {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        null,
                        null
                    )
                    return@withContext targetFile
                }
            }

            // Exclusively handled by yt-dlp & gallery-dl. Return null if both failed.
            null
        } catch (e: Exception) {
            Log.e(TAG, "downloadMedia failed", e)
            null
        }
    }


    private data class NativeYtDlpAttempt(
        val handled: Boolean,
        val file: File?
    )

    /**
     * yt-dlp resolves the best formats; OkHttp downloads the selected tracks and
     * the bundled Android-native FFmpegKit muxes/validates separate A/V streams.
     * This avoids attempting subprocess FFmpeg execution inside Chaquopy.
     */
    private suspend fun downloadWithNativeYtDlp(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String?,
        userAgent: String,
        cookies: String,
        headers: Map<String, String>,
        domain: String
    ): NativeYtDlpAttempt = withContext(Dispatchers.IO) {
        var tempDir: File? = null
        var resolverSucceeded = false
        try {
            val effectiveHeaders = headers.toMutableMap().apply {
                if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                if (cookies.isNotBlank()) put("Cookie", cookies)
                if (!referer.isNullOrBlank()) put("Referer", referer)
            }
            val headersJson = JSONObject().apply {
                effectiveHeaders.forEach { (key, value) -> put(key, value) }
            }.toString()

            val runner = Python.getInstance().getModule("gallery_dl_runner")
            val response = runner.callAttr(
                "resolve_ytdlp_formats",
                mediaUrl,
                referer ?: "",
                userAgent,
                cookies,
                headersJson
            ).toString()
            val resolved = JSONObject(response)
            if (!resolved.optBoolean("success", false)) {
                return@withContext NativeYtDlpAttempt(false, null)
            }
            resolverSucceeded = true

            fun streamHeaders(spec: JSONObject): Map<String, String> {
                val result = effectiveHeaders.toMutableMap()
                val streamHeaders = spec.optJSONObject("headers") ?: return result
                val keys = streamHeaders.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = streamHeaders.optString(key, "")
                    if (key.isNotBlank() && value.isNotBlank()) result[key] = value
                }
                return result
            }

            suspend fun downloadPart(spec: JSONObject, role: String, directory: File): File? {
                val url = spec.optString("url", "")
                if (url.isBlank()) return null
                val rawExt = spec.optString("ext", "bin").lowercase()
                val extension = rawExt.takeIf { it.matches(Regex("[a-z0-9]{2,8}")) } ?: "bin"
                val result = OkHttpDownloader.downloadMediaFileDetailed(
                    context = context,
                    mediaUrl = url,
                    fileName = "$role.$extension",
                    referer = resolved.optString("pageUrl").takeIf { it.isNotBlank() } ?: referer,
                    headers = streamHeaders(spec),
                    domain = domain,
                    targetDirectory = directory
                )
                return result.file?.takeIf { result.isSuccessful && it.isFile && it.length() > 0L }
            }

            val outputDirectory = OkHttpDownloader.getTargetStorageDir(context, domain)
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "media" }
            val baseName = safeName.substringBeforeLast('.', safeName)
            val mode = resolved.optString("mode", "")

            if (mode == "mux") {
                val videoSpec = resolved.optJSONObject("video")
                    ?: return@withContext NativeYtDlpAttempt(true, null)
                val audioSpec = resolved.optJSONObject("audio")
                    ?: return@withContext NativeYtDlpAttempt(true, null)

                tempDir = File(context.cacheDir, "yt-dlp-mux-${mediaUrl.hashCode().toUInt()}-${System.nanoTime()}")
                if (!tempDir!!.mkdirs() && !tempDir!!.isDirectory) {
                    return@withContext NativeYtDlpAttempt(true, null)
                }

                val videoFile = downloadPart(videoSpec, "video", tempDir!!)
                    ?: return@withContext NativeYtDlpAttempt(true, null)
                val audioFile = downloadPart(audioSpec, "audio", tempDir!!)
                    ?: return@withContext NativeYtDlpAttempt(true, null)

                val finalFile = File(outputDirectory, "${System.currentTimeMillis()}_${baseName}.mp4")
                val stagingDirectory = File(outputDirectory, ".ffmpeg-staging")
                if (!stagingDirectory.exists()) stagingDirectory.mkdirs()
                val temporaryOutputDirectory = if (stagingDirectory.isDirectory) stagingDirectory else tempDir!!
                val temporaryOutput = File(temporaryOutputDirectory, ".${finalFile.nameWithoutExtension}-${System.nanoTime()}.part.mp4")
                temporaryOutput.delete()
                val muxed = FfmpegKitEngine.muxAudioAndVideo(
                    videoFile = videoFile,
                    audioFile = audioFile,
                    outputFile = temporaryOutput
                )
                if (!muxed || !temporaryOutput.isFile || temporaryOutput.length() <= 0L) {
                    temporaryOutput.delete()
                    return@withContext NativeYtDlpAttempt(true, null)
                }
                val publishTemp = File(outputDirectory, ".${finalFile.name}.${System.nanoTime()}.publishing")
                val published = if (temporaryOutput.renameTo(finalFile)) {
                    true
                } else {
                    runCatching {
                        temporaryOutput.copyTo(publishTemp, overwrite = true)
                        publishTemp.isFile && publishTemp.length() == temporaryOutput.length() && publishTemp.renameTo(finalFile)
                    }.getOrDefault(false).also {
                        publishTemp.delete()
                        if (it) temporaryOutput.delete()
                    }
                }
                if (!published) {
                    temporaryOutput.delete()
                    publishTemp.delete()
                    finalFile.delete()
                    return@withContext NativeYtDlpAttempt(true, null)
                }
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(finalFile.absolutePath), arrayOf("video/mp4"), null
                )
                return@withContext NativeYtDlpAttempt(true, finalFile)
            }

            if (mode == "single") {
                val formatSpec = resolved.optJSONObject("format")
                    ?: return@withContext NativeYtDlpAttempt(true, null)
                val streamUrl = formatSpec.optString("url", "")
                if (streamUrl.isBlank()) return@withContext NativeYtDlpAttempt(true, null)

                val isManifest = streamUrl.contains(".m3u8", ignoreCase = true) ||
                    streamUrl.contains(".mpd", ignoreCase = true)
                if (isManifest) {
                    val finalFile = File(outputDirectory, "${System.currentTimeMillis()}_${baseName}.mp4")
                    val remux = FfmpegKitEngine.remuxStream(
                        streamUrl = streamUrl,
                        outputFile = finalFile,
                        context = context,
                        headers = streamHeaders(formatSpec)
                    )
                    return@withContext NativeYtDlpAttempt(
                        true,
                        finalFile.takeIf { remux.isSuccess && it.isFile && it.length() > 0L }
                    )
                }

                val ext = formatSpec.optString("ext", safeName.substringAfterLast('.', "mp4")).lowercase()
                val existingExtension = safeName.substringAfterLast('.', "")
                val outputName = if (existingExtension.equals(ext, ignoreCase = true)) {
                    safeName
                } else {
                    "$baseName.$ext"
                }
                val directResult = OkHttpDownloader.downloadMediaFileDetailed(
                    context = context,
                    mediaUrl = streamUrl,
                    fileName = outputName,
                    referer = resolved.optString("pageUrl").takeIf { it.isNotBlank() } ?: referer,
                    headers = streamHeaders(formatSpec),
                    domain = domain
                )
                val downloaded = directResult.file
                if (!directResult.isSuccessful || downloaded == null || !downloaded.isFile || downloaded.length() <= 0L) {
                    return@withContext NativeYtDlpAttempt(true, null)
                }

                val expectsVideo = resolved.optBoolean("hasVideo", false)
                val expectsAudio = resolved.optBoolean("hasAudio", false)
                if (expectsVideo || expectsAudio) {
                    val info = FfmpegKitEngine.probeMedia(downloaded.absolutePath)
                    val valid = info != null &&
                        (!expectsVideo || !info.videoCodec.isNullOrBlank()) &&
                        (!expectsAudio || !info.audioCodec.isNullOrBlank()) &&
                        info.durationSeconds > 0.0 &&
                        info.sizeBytes > 0L
                    if (!valid) {
                        downloaded.delete()
                        return@withContext NativeYtDlpAttempt(true, null)
                    }
                }
                return@withContext NativeYtDlpAttempt(true, downloaded)
            }

            NativeYtDlpAttempt(false, null)
        } catch (e: Exception) {
            Log.w(TAG, "Native yt-dlp/FFmpeg path failed for media URL: ${e.message}")
            NativeYtDlpAttempt(resolverSucceeded, null)
        } finally {
            tempDir?.let { runCatching { it.deleteRecursively() } }
        }
    }

    fun defaultPopularSites(): List<GalleryDlSite> = listOf(
        GalleryDlSite("YouTube", listOf("youtube.com", "youtu.be"), "smart_display"),
        GalleryDlSite("TikTok", listOf("tiktok.com"), "music_note"),
        GalleryDlSite("Instagram", listOf("instagram.com"), "camera"),
        GalleryDlSite("Reddit", listOf("reddit.com", "redd.it", "i.redd.it", "v.redd.it"), "reddit"),
        GalleryDlSite("Twitter / X", listOf("twitter.com", "x.com", "pbs.twimg.com"), "tag"),
        GalleryDlSite("Imgur", listOf("imgur.com", "i.imgur.com"), "image"),
        GalleryDlSite("Pinterest", listOf("pinterest.com", "i.pinimg.com"), "pin"),
        GalleryDlSite("ArtStation", listOf("artstation.com"), "palette"),
        GalleryDlSite("DeviantArt", listOf("deviantart.com"), "brush"),
        GalleryDlSite("Vimeo", listOf("vimeo.com"), "movie"),
        GalleryDlSite("SoundCloud", listOf("soundcloud.com"), "audiotrack"),
        GalleryDlSite("Danbooru", listOf("donmai.us", "gelbooru.com", "safebooru.org", "yande.re"), "collections"),
        GalleryDlSite("Flickr", listOf("flickr.com"), "photo_camera"),
        GalleryDlSite("Tumblr", listOf("tumblr.com"), "feed"),
        GalleryDlSite("Wikimedia", listOf("wikimedia.org", "wikipedia.org"), "public"),
        GalleryDlSite("Bluesky", listOf("bsky.app"), "cloud"),
        GalleryDlSite("500px", listOf("500px.com"), "camera_alt"),
        GalleryDlSite("Behance", listOf("behance.net"), "design_services"),
        GalleryDlSite("Bilibili", listOf("bilibili.com"), "smart_display"),
        GalleryDlSite("4chan", listOf("4chan.org", "4channel.org", "i.4cdn.org"), "forum")
    )
}
