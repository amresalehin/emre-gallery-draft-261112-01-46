package com.amresalehin.emregallery.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.os.Build
import com.amresalehin.emregallery.data.AdBlockEngine
import com.amresalehin.emregallery.data.BotDetectionMitigator
import com.amresalehin.emregallery.data.BrowserHistoryManager
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserShortcut
import com.amresalehin.emregallery.data.BrowserTabItem
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PocketDeepScanEngine
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectBrowserView(
    initialUrl: String = "",
    onClose: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onPageTitleChanged: ((String) -> Unit)? = null,
    onMediaExtracted: (ScrapedMediaItem) -> Unit,
    onBatchExtracted: (List<ScrapedMediaItem>, pageTitle: String?, domain: String?) -> Unit,
    onSwitchToMediaTab: () -> Unit = {},
    onOpenDownloads: (() -> Unit)? = null,
    onOpenDiscoveredMedia: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabDetectedCount by tabSession.detectedCount.collectAsState()
    val sessionDiscoveredItems by DownloaderSession.discoveredItems.collectAsState()

    val prefsRepo = remember { PreferencesRepository(context) }
    val themePref by prefsRepo.theme.collectAsState(initial = "system")
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> systemInDark
    }

    val shortcutsList by historyManager.shortcuts.collectAsState(initial = historyManager.defaultShortcuts)
    val allHistorySites by historyManager.allSites.collectAsState(initial = historyManager.defaultRecentSites)
    val tabsList by historyManager.openTabs.collectAsState(initial = historyManager.defaultTabs)

    val startUrl = remember {
        val savedSite = BrowserSession.savedBrowserSite.trim()
        val trimmed = initialUrl.trim()
        when {
            savedSite.isNotBlank() -> savedSite
            trimmed.isNotBlank() -> MediaClassifier.formatQueryOrUrl(trimmed)
            else -> "" // Clean monochrome home page by default
        }
    }

    // Discovery & Deep Scan states
    var isDeepScanning by remember { mutableStateOf(false) }
    var hasInitiatedScanInStage by remember { mutableStateOf(false) }
    var deepScanJob by remember { mutableStateOf<Job?>(null) }

    var currentUrl by remember { mutableStateOf(startUrl) }
    val totalDetectedCount = remember(currentUrl, tabDetectedCount, sessionDiscoveredItems.size) {
        if (currentUrl.isBlank() || currentUrl == "about:blank") 0
        else maxOf(tabDetectedCount, sessionDiscoveredItems.size)
    }
    var addressInput by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Dialog & Sheet States
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var selectedShortcutForEdit by remember { mutableStateOf<BrowserShortcut?>(null) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiscoveredMediaOverlay by remember { mutableStateOf(false) }
    var activeTabId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tabsList) {
        if (tabsList.isEmpty()) {
            val newId = "tab_${System.currentTimeMillis()}"
            val initialTab = BrowserTabItem(
                id = newId,
                title = if (pageTitle.isNotBlank()) pageTitle else if (currentUrl.isNotBlank()) MediaClassifier.cleanDomainFromUrl(currentUrl) else "Home",
                url = currentUrl
            )
            activeTabId = newId
            historyManager.saveTabs(listOf(initialTab))
        } else if (activeTabId == null || tabsList.none { it.id == activeTabId }) {
            val matching = tabsList.firstOrNull { it.url.isNotBlank() && it.url.equals(currentUrl, ignoreCase = true) }
                ?: tabsList.first()
            activeTabId = matching.id
        }
    }

    fun syncCurrentTabWithPage(targetUrl: String, targetTitle: String) {
        val tabId = activeTabId ?: return
        val currentTabs = tabsList
        val existing = currentTabs.firstOrNull { it.id == tabId }
        val displayTitle = if (targetTitle.isNotBlank() && targetTitle != "about:blank") {
            targetTitle
        } else if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
            MediaClassifier.cleanDomainFromUrl(targetUrl)
        } else {
            "Home"
        }
        val cleanUrl = if (targetUrl == "about:blank") "" else targetUrl
        if (existing == null || existing.url != cleanUrl || existing.title != displayTitle) {
            val updatedTabs = if (existing == null) {
                currentTabs + BrowserTabItem(id = tabId, title = displayTitle, url = cleanUrl)
            } else {
                currentTabs.map {
                    if (it.id == tabId) it.copy(title = displayTitle, url = cleanUrl, timestamp = System.currentTimeMillis())
                    else it
                }
            }
            coroutineScope.launch {
                historyManager.saveTabs(updatedTabs)
            }
        }
    }

    val currentView = LocalView.current
    DisposableEffect(isDark) {
        val window = (currentView.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, currentView)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }

    val scrollHandler = remember { BrowserToolbarScrollHandler() }

    LaunchedEffect(currentUrl) {
        if (currentUrl.isBlank()) {
            scrollHandler.show()
        }
    }

    LaunchedEffect(Unit) {
        if (startUrl.isBlank()) {
            tabSession.clearMedia()
            tabSession.updateNavigation("")
            DownloaderSession.clear()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.destroy()
                webViewInstance = null
            }
        }
    }

    fun returnToBrowserHome() {
        currentUrl = ""
        addressInput = ""
        pageTitle = ""
        canGoBack = false
        canGoForward = false
        onUrlChange?.invoke("")
        tabSession.clearMedia()
        tabSession.updateNavigation("")
        DownloaderSession.clear()
        scrollHandler.show()
        webViewInstance?.clearHistory()
        webViewInstance?.loadUrl("about:blank")
    }

    // Synchronized back navigation for DirectBrowserView (shared by native BackHandler and search bar back button)
    fun handleBrowserBackNavigation() {
        when {
            showDiscoveredMediaOverlay -> {
                showDiscoveredMediaOverlay = false
                deepScanJob?.cancel()
                deepScanJob = null
                isDeepScanning = false
                hasInitiatedScanInStage = false
            }
            showHistorySheet -> showHistorySheet = false
            showTabsSheet -> showTabsSheet = false
            showSettingsSheet -> showSettingsSheet = false
            showAddShortcutDialog -> showAddShortcutDialog = false
            selectedShortcutForEdit != null -> selectedShortcutForEdit = null
            currentUrl.isNotBlank() -> {
                if (canGoBack && webViewInstance?.canGoBack() == true) {
                    webViewInstance?.goBack()
                } else {
                    returnToBrowserHome()
                }
            }
            onClose != null -> onClose()
        }
    }

    // Intercept back button when browser can navigate back, return to home, dismiss a sheet, or exit the browser
    BackHandler(
        enabled = showDiscoveredMediaOverlay || showHistorySheet || showTabsSheet || showSettingsSheet ||
                showAddShortcutDialog || selectedShortcutForEdit != null ||
                currentUrl.isNotBlank() || onClose != null
    ) {
        handleBrowserBackNavigation()
    }

    fun scanCurrentPageDOM(wv: WebView?, onCompleted: (() -> Unit)? = null) {
        if (wv == null) {
            onCompleted?.invoke()
            return
        }
        val current = wv.url ?: run {
            onCompleted?.invoke()
            return
        }
        if (current.isBlank() || current == "about:blank" || currentUrl.isBlank()) {
            onCompleted?.invoke()
            return
        }

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            val discovered = MediaCaptureEngine.parseJsDomScanResult(jsonRaw, current)
            if (discovered.isNotEmpty()) {
                val domain = runCatching { URL(current).host }.getOrDefault("")
                tabSession.registerMediaBatch(discovered, pageTitle)
                onBatchExtracted(discovered, pageTitle, domain)
                DownloaderSession.addDiscoveredItems(discovered, pageTitle)
            }
            onCompleted?.invoke()
        }
    }

    fun stopBrowserDeepScan() {
        deepScanJob?.cancel()
        deepScanJob = null
        PocketDeepScanEngine.stopScan()
        isDeepScanning = false
    }

    fun startBrowserDeepScan() {
        val current = currentUrl.ifBlank { webViewInstance?.url.orEmpty() }
        if (current.isBlank() || current == "about:blank") return

        stopBrowserDeepScan()
        isDeepScanning = true
        hasInitiatedScanInStage = true

        // 1. Immediately scan active DOM on the visible WebView WITHOUT any visual scrolling
        // Promote lazy attributes in-place without moving the visible viewport
        webViewInstance?.let { wv ->
            wv.evaluateJavascript(
                """
                (function() {
                    try {
                        var lazyImgs = document.querySelectorAll('img[data-src], img[data-original], img[data-lazy], img[data-url], img[data-highres]');
                        for (var i = 0; i < lazyImgs.length; i++) {
                            var img = lazyImgs[i];
                            var realSrc = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy') || img.getAttribute('data-url') || img.getAttribute('data-highres');
                            if (realSrc && !img.src) img.src = realSrc;
                        }
                        var lazyVideos = document.querySelectorAll('video[data-src], source[data-src], video[data-url], source[data-url]');
                        for (var j = 0; j < lazyVideos.length; j++) {
                            var v = lazyVideos[j];
                            var vSrc = v.getAttribute('data-src') || v.getAttribute('data-url');
                            if (vSrc && !v.src) v.src = vSrc;
                        }
                        window.dispatchEvent(new Event('scroll'));
                        window.dispatchEvent(new Event('resize'));
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
            scanCurrentPageDOM(wv)
        }

        // 2. Run isolated background deep scan with full scrolling & JS offscreen
        // The active browser viewport NEVER scrolls; the user's reading view remains 100% still!
        PocketDeepScanEngine.startScan(
            context = context,
            bookmarkId = "browser_active_${System.currentTimeMillis()}",
            targetUrl = current,
            scope = coroutineScope,
            onIncrementalMediaDiscovered = { newItems ->
                val scraped = newItems.map { item ->
                    ScrapedMediaItem(
                        id = item.id.ifBlank { "deep_${item.sourceUrl.hashCode()}" },
                        url = item.sourceUrl,
                        title = item.title.ifBlank { pageTitle },
                        source = ExtractionSource.DOM_SCRAPE.label,
                        isVideo = item.isVideo || item.isStream,
                        isAudio = item.isAudio,
                        isStream = item.isStream,
                        sizeBytes = item.sizeBytes
                    )
                }
                tabSession.registerMediaBatch(scraped, pageTitle)
                val domain = runCatching { URL(current).host }.getOrDefault("")
                onBatchExtracted(scraped, pageTitle, domain)
                DownloaderSession.addDiscoveredItems(scraped, pageTitle)
            },
            onCompleted = {
                isDeepScanning = false
            }
        )
    }

    fun handleDiscoveredMediaClick() {
        if (!hasInitiatedScanInStage) {
            // First tap: Do NOT open the discovery page immediately.
            // On tapping update media counts via explicitly requested 30s deep scan.
            startBrowserDeepScan()
        } else {
            // Tapping discover button again in this stage opens the discovery page.
            stopBrowserDeepScan()

            val wv = webViewInstance
            val site = currentUrl.ifBlank { wv?.url.orEmpty() }

            // Save last scroll position and site before opening discovery page
            val scrollX = wv?.scrollX ?: 0
            val scrollY = wv?.scrollY ?: 0

            BrowserSession.savedBrowserSite = site
            BrowserSession.savedScrollX = scrollX
            BrowserSession.savedScrollY = scrollY
            BrowserSession.pendingScrollRestore = true

            // Also probe JavaScript for high-precision scroll offsets
            wv?.evaluateJavascript(
                "(function() { return JSON.stringify({ x: window.pageXOffset || 0, y: window.pageYOffset || 0 }); })();"
            ) { rawJson ->
                try {
                    if (!rawJson.isNullOrBlank() && rawJson != "null") {
                        val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                            org.json.JSONObject(org.json.JSONTokener(rawJson).nextValue().toString())
                        } else {
                            org.json.JSONObject(rawJson)
                        }
                        val jsX = clean.optInt("x", scrollX)
                        val jsY = clean.optInt("y", scrollY)
                        if (jsY > 0) BrowserSession.savedScrollY = jsY
                        if (jsX > 0) BrowserSession.savedScrollX = jsX
                    }
                } catch (_: Throwable) {}
            }

            // Open discovered media page without killing webpage for super fast instant return
            showDiscoveredMediaOverlay = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopBrowserDeepScan()
        }
    }

    // Reset to idle state and restore scroll position when returning from discovery page
    LaunchedEffect(Unit) {
        if (BrowserSession.pendingScrollRestore && BrowserSession.savedBrowserSite.isNotBlank()) {
            isDeepScanning = false
            hasInitiatedScanInStage = false
            deepScanJob?.cancel()
            deepScanJob = null

            val targetX = BrowserSession.savedScrollX
            val targetY = BrowserSession.savedScrollY

            webViewInstance?.let { wv ->
                wv.postDelayed({
                    wv.scrollTo(targetX, targetY)
                    wv.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                }, 100)
                wv.postDelayed({
                    wv.scrollTo(targetX, targetY)
                    wv.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                }, 350)
                wv.postDelayed({
                    wv.scrollTo(targetX, targetY)
                    wv.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                }, 750)
            }
            BrowserSession.pendingScrollRestore = false
        }
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        stopBrowserDeepScan()
        hasInitiatedScanInStage = false
        BrowserSession.pendingScrollRestore = false
        BrowserSession.savedBrowserSite = ""
        scrollHandler.show()
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed)
        tabSession.clearMedia()
        DownloaderSession.clear()
        addressInput = formatted
        currentUrl = formatted
        onUrlChange?.invoke(formatted)
        tabSession.updateNavigation(formatted, pageTitle)
        DownloaderSession.updateBrowserPage(formatted)
        syncCurrentTabWithPage(formatted, pageTitle)
        coroutineScope.launch {
            historyManager.recordVisit(formatted)
        }
        webViewInstance?.loadUrl(formatted)
        focusManager.clearFocus()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("direct_browser_view"),
        color = BrowserThemeColors.background(isDark)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The toolbar is a fixed layout section; webpage content begins below it.
            Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .background(BrowserThemeColors.background(isDark))
                    .testTag("browser_pinned_top_controls")
            ) {
            // 1. Minimalist Monochrome Top Bar (always visible)
            BrowserTopBar(
                addressInput = addressInput,
                onAddressChange = { addressInput = it },
                onNavigate = { navigateToUrl(it) },
                onHamburgerClick = {
                    if (onMenuClick != null) {
                        onMenuClick.invoke()
                    } else {
                        showTabsSheet = true
                    }
                },
                onDiscoveredMediaClick = {
                    handleDiscoveredMediaClick()
                },
                discoveredCount = totalDetectedCount,
                isDeepScanning = isDeepScanning,
                onNewTabClick = {
                    val newId = "tab_${System.currentTimeMillis()}"
                    val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                    activeTabId = newId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(tabsList + newTab)
                    }
                },
                onReloadClick = {
                    if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                },
                onPinCurrentSite = {
                    if (currentUrl.isNotBlank()) {
                        coroutineScope.launch {
                            historyManager.addShortcut(pageTitle.ifBlank { currentUrl }, currentUrl)
                        }
                    }
                },
                onSaveToPocket = {
                    if (currentUrl.isNotBlank()) {
                        val pocketRepo = com.amresalehin.emregallery.data.PocketRepository.getInstance(context)
                        val effectiveTitle = pageTitle.ifBlank { webViewInstance?.title ?: currentUrl }
                        
                        webViewInstance?.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var ogImg = document.querySelector('meta[property="og:image"]')?.content ||
                                                document.querySelector('meta[name="og:image"]')?.content ||
                                                document.querySelector('meta[name="twitter:image"]')?.content ||
                                                document.querySelector('link[rel="apple-touch-icon"]')?.href || '';
                                    var ogDesc = document.querySelector('meta[property="og:description"]')?.content ||
                                                 document.querySelector('meta[name="description"]')?.content || '';
                                    return JSON.stringify({ image: ogImg, desc: ogDesc });
                                } catch(e) {
                                    return JSON.stringify({ image: '', desc: '' });
                                }
                            })();
                            """.trimIndent()
                        ) { resultJson ->
                            var previewImg = ""
                            var pageDesc = ""
                            if (!resultJson.isNullOrBlank() && resultJson != "null") {
                                try {
                                    val unquoted = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                        org.json.JSONTokener(resultJson).nextValue().toString()
                                    } else {
                                        resultJson
                                    }
                                    val json = org.json.JSONObject(unquoted)
                                    previewImg = json.optString("image", "")
                                    pageDesc = json.optString("desc", "")
                                } catch (_: Exception) {}
                            }

                            pocketRepo.addBookmark(
                                title = effectiveTitle,
                                url = currentUrl,
                                description = pageDesc,
                                previewImageUrl = previewImg,
                                autoFetchMetadata = true
                            )
                        } ?: run {
                            pocketRepo.addBookmark(
                                title = effectiveTitle,
                                url = currentUrl,
                                autoFetchMetadata = true
                            )
                        }
                        Toast.makeText(context, "Saved to Pocket", Toast.LENGTH_SHORT).show()
                    }
                },
                onShareClick = {
                    if (currentUrl.isNotBlank()) {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                    }
                },
                onDesktopToggle = { desktop ->
                    isDesktopMode = desktop
                    webViewInstance?.settings?.apply {
                        userAgentString = if (desktop) {
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        } else {
                            BotDetectionMitigator.STEALTH_USER_AGENT
                        }
                        useWideViewPort = desktop
                        loadWithOverviewMode = desktop
                    }
                    webViewInstance?.reload()
                },
                isDesktopMode = isDesktopMode,
                onClearHistoryClick = {
                    coroutineScope.launch {
                        historyManager.clearHistory()
                    }
                },
                onSettingsClick = {
                    showSettingsSheet = true
                },
                isWebPage = currentUrl.isNotBlank(),
                onHomeClick = {
                    handleBrowserBackNavigation()
                },
                onCloseBrowser = onClose,
                tabCount = tabsList.size,
                onOpenDownloads = {
                    onOpenDownloads?.invoke() ?: onSwitchToMediaTab()
                },
                onOpenHistory = {
                    showHistorySheet = true
                },
                onOpenTabs = {
                    showTabsSheet = true
                },
                isDark = isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Loading Progress Bar
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadingProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = BrowserThemeColors.textPrimary(isDark),
                    trackColor = BrowserThemeColors.divider(isDark)
                )
            }

            }

            val isChallengeActive = remember(pageTitle, currentUrl) {
                BotDetectionMitigator.isBotChallenge(pageTitle, currentUrl)
            }
            AnimatedVisibility(
                visible = isChallengeActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("browser_bot_challenge_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF92400E),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Security Verification: Tap captcha checkbox if prompted to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // The browser viewport uses the remaining height beneath the toolbar.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Interactive WebView (always instantiated for high-speed responsiveness)
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            BrowserSession.activeWebView = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                            }
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = BotDetectionMitigator.STEALTH_USER_AGENT
                            }

                            addJavascriptInterface(
                                BrowserSession.EmreMediaBridge { rawUrl ->
                                    val item = MediaCaptureEngine.buildMediaItem(
                                        rawUrl = rawUrl,
                                        source = ExtractionSource.DOM_SCRAPE,
                                        baseUrl = currentUrl
                                    )
                                    if (item != null) {
                                        tabSession.registerMedia(item)
                                    }
                                },
                                "EmreBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                    onPageTitleChanged?.invoke(pageTitle)
                                    tabSession.updateNavigation(currentUrl, pageTitle)
                                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                        syncCurrentTabWithPage(currentUrl, pageTitle)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                private fun handleExternalNavigation(view: WebView?, urlString: String): Boolean {
                                    val lower = urlString.trim().lowercase()
                                    if (lower.startsWith("http://") || lower.startsWith("https://") ||
                                        lower.startsWith("about:") || lower.startsWith("data:") ||
                                        lower.startsWith("javascript:") || lower.startsWith("blob:") ||
                                        lower.startsWith("file:///android_asset/")
                                    ) {
                                        return false
                                    }

                                    val context = view?.context ?: return false

                                    if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                                        try {
                                            val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                                            if (intent != null) {
                                                val pm = context.packageManager
                                                val resolveInfo = intent.resolveActivity(pm)
                                                if (resolveInfo != null) {
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                }

                                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                val packageName = intent.`package`
                                                if (!packageName.isNullOrBlank()) {
                                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    if (marketIntent.resolveActivity(pm) != null) {
                                                        context.startActivity(marketIntent)
                                                        return true
                                                    } else {
                                                        view?.loadUrl("https://play.google.com/store/apps/details?id=$packageName")
                                                        return true
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DirectBrowserView", "Failed to resolve intent scheme: $urlString", e)
                                        }
                                        return true
                                    }

                                    try {
                                        val uri = Uri.parse(urlString)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val pm = context.packageManager
                                        if (intent.resolveActivity(pm) != null) {
                                            context.startActivity(intent)
                                            return true
                                        } else if (lower.startsWith("market://details?id=")) {
                                            val pkg = uri.getQueryParameter("id")
                                            if (!pkg.isNullOrBlank()) {
                                                view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                                                return true
                                            }
                                        }
                                    } catch (e: ActivityNotFoundException) {
                                        Log.w("DirectBrowserView", "No app available to handle scheme: $urlString", e)
                                        return true
                                    } catch (e: Exception) {
                                        Log.w("DirectBrowserView", "Failed to handle external URI: $urlString", e)
                                        return true
                                    }

                                    return true
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    return handleExternalNavigation(view, reqUrl)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    return handleExternalNavigation(view, url)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    scrollHandler.show()
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.clearMedia()
                                        tabSession.updateNavigation("")
                                        DownloaderSession.clear()
                                        isLoading = false
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    tabSession.clearMedia()
                                    DownloaderSession.clear()
                                    isLoading = true
                                    BotDetectionMitigator.injectStealth(view)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    tabSession.updateNavigation(url, pageTitle)
                                    DownloaderSession.updateBrowserPage(url)
                                    syncCurrentTabWithPage(url, pageTitle)
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.updateNavigation("")
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    BotDetectionMitigator.injectStealth(view)
                                    BotDetectionMitigator.syncCookiesFromWebView(url)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    val finalTitle = view?.title.orEmpty().ifBlank { pageTitle }
                                    pageTitle = finalTitle
                                    tabSession.updateNavigation(url, finalTitle)
                                    syncCurrentTabWithPage(url, finalTitle)
                                    coroutineScope.launch {
                                        historyManager.recordVisit(url, view?.title)
                                    }
                                    if (BrowserSession.pendingScrollRestore && BrowserSession.savedBrowserSite.isNotBlank() &&
                                        url?.equals(BrowserSession.savedBrowserSite, ignoreCase = true) == true) {
                                        val targetX = BrowserSession.savedScrollX
                                        val targetY = BrowserSession.savedScrollY
                                        view?.postDelayed({
                                            view.scrollTo(targetX, targetY)
                                            view.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                                        }, 150)
                                        view?.postDelayed({
                                            view.scrollTo(targetX, targetY)
                                            view.evaluateJavascript("window.scrollTo($targetX, $targetY);", null)
                                        }, 500)
                                        BrowserSession.pendingScrollRestore = false
                                        isDeepScanning = false
                                        hasInitiatedScanInStage = false
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    if (reqUrl.startsWith("about:")) return null

                                    // Ad & tracker blocking for lightweight, high-performance browsing
                                    if (AdBlockEngine.isAdOrTracker(reqUrl)) {
                                        return AdBlockEngine.createEmptyResponse()
                                    }

                                    return null
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    val didCrash = detail?.didCrash() ?: false
                                    Log.w("DirectBrowserView", "Chromium renderer process gone (didCrash=$didCrash). Cleanly recovering...")
                                    try {
                                        view?.let { wv ->
                                            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                            wv.destroy()
                                        }
                                    } catch (_: Throwable) {}
                                    if (webViewInstance === view) {
                                        webViewInstance = null
                                    }
                                    return true
                                }
                            }

                            if (startUrl.isNotBlank()) {
                                loadUrl(startUrl)
                            }
                        }
                    },
                    update = { wv ->
                        webViewInstance = wv
                        BrowserSession.activeWebView = wv
                        wv.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            wv.settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                        wv.webChromeClient = WebChromeClient()
                        wv.destroy()
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )

                // 2. Minimalist Monochrome Mobile Browser Home Layout (blank when empty)
                if (currentUrl.isBlank()) {
                    BrowserHomeContent(
                        shortcuts = shortcutsList,
                        recentSites = allHistorySites,
                        onNavigate = { navigateToUrl(it) },
                        onAddShortcutClick = { showAddShortcutDialog = true },
                        onEditShortcutClick = { selectedShortcutForEdit = it },
                        onSeeAllHistoryClick = { showHistorySheet = true },
                        isDark = isDark,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_empty_home_page")
                    )
                }
            }
        }

        // Fullscreen Discovered Media Overlay (keeps underlying WebView alive for instant return & deep scanning)
        if (showDiscoveredMediaOverlay) {
            DiscoveredMediaPage(
                onBack = {
                    showDiscoveredMediaOverlay = false
                    stopBrowserDeepScan()
                    hasInitiatedScanInStage = false
                },
                onDeepScanClick = {
                    if (isDeepScanning) {
                        stopBrowserDeepScan()
                    } else {
                        startBrowserDeepScan()
                    }
                },
                isDeepScanning = isDeepScanning,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
            )
        }
    }
}

    // Modal Dialogs & Sheets
    if (showAddShortcutDialog) {
        AddShortcutDialog(
            onDismiss = { showAddShortcutDialog = false },
            onAdd = { title, url ->
                coroutineScope.launch {
                    historyManager.addShortcut(title, url)
                }
            },
            isDark = isDark
        )
    }

    selectedShortcutForEdit?.let { sc ->
        EditShortcutDialog(
            shortcut = sc,
            onDismiss = { selectedShortcutForEdit = null },
            onUpdate = { title, url ->
                coroutineScope.launch {
                    historyManager.updateShortcut(sc.id, title, url)
                }
            },
            onDelete = {
                coroutineScope.launch {
                    historyManager.removeShortcut(sc.id)
                }
            },
            isDark = isDark
        )
    }

    if (showHistorySheet) {
        BrowserHistoryBottomSheet(
            history = allHistorySites,
            onNavigate = { navigateToUrl(it) },
            onDeleteSite = { url ->
                coroutineScope.launch {
                    historyManager.deleteSite(url)
                }
            },
            onClearAll = {
                coroutineScope.launch {
                    historyManager.clearHistory()
                }
            },
            onDismiss = { showHistorySheet = false },
            isDark = isDark
        )
    }

    if (showTabsSheet) {
        BrowserTabsBottomSheet(
            tabs = tabsList,
            currentUrl = currentUrl,
            activeTabId = activeTabId,
            onSelectTab = { tab ->
                activeTabId = tab.id
                currentUrl = tab.url
                addressInput = tab.url
                pageTitle = tab.title
                onUrlChange?.invoke(tab.url)
                tabSession.clearMedia()
                tabSession.updateNavigation(tab.url, tab.title)
                DownloaderSession.clear()
                scrollHandler.show()
                if (tab.url.isNotBlank()) {
                    webViewInstance?.loadUrl(tab.url)
                } else {
                    webViewInstance?.loadUrl("about:blank")
                }
            },
            onCloseTab = { tabId ->
                val remaining = tabsList.filter { it.id != tabId }
                if (remaining.isEmpty()) {
                    val freshId = "tab_${System.currentTimeMillis()}"
                    val freshTab = BrowserTabItem(id = freshId, title = "New Tab", url = "")
                    activeTabId = freshId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(listOf(freshTab))
                    }
                } else {
                    if (activeTabId == tabId) {
                        val nextTab = remaining.last()
                        activeTabId = nextTab.id
                        currentUrl = nextTab.url
                        addressInput = nextTab.url
                        pageTitle = nextTab.title
                        onUrlChange?.invoke(nextTab.url)
                        tabSession.clearMedia()
                        tabSession.updateNavigation(nextTab.url, nextTab.title)
                        DownloaderSession.clear()
                        scrollHandler.show()
                        if (nextTab.url.isNotBlank()) {
                            webViewInstance?.loadUrl(nextTab.url)
                        } else {
                            webViewInstance?.loadUrl("about:blank")
                        }
                    }
                    coroutineScope.launch {
                        historyManager.saveTabs(remaining)
                    }
                }
            },
            onNewTab = {
                val newId = "tab_${System.currentTimeMillis()}"
                val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                activeTabId = newId
                currentUrl = ""
                addressInput = ""
                pageTitle = ""
                onUrlChange?.invoke("")
                tabSession.clearMedia()
                tabSession.updateNavigation("")
                DownloaderSession.clear()
                scrollHandler.show()
                webViewInstance?.loadUrl("about:blank")
                coroutineScope.launch {
                    historyManager.saveTabs(tabsList + newTab)
                }
            },
            onDismiss = { showTabsSheet = false },
            isDark = isDark
        )
    }

    if (showSettingsSheet) {
        BrowserSettingsBottomSheet(
            isDesktopMode = isDesktopMode,
            onDesktopModeChange = { desktop ->
                isDesktopMode = desktop
                webViewInstance?.settings?.apply {
                    userAgentString = if (desktop) {
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } else {
                        BotDetectionMitigator.STEALTH_USER_AGENT
                    }
                    useWideViewPort = desktop
                    loadWithOverviewMode = desktop
                }
                webViewInstance?.reload()
            },
            onClearCacheClick = {
                webViewInstance?.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            },
            onDismiss = { showSettingsSheet = false },
            isDark = isDark
        )
    }
}
