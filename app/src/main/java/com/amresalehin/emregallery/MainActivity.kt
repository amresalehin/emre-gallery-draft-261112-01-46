package com.amresalehin.emregallery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.GalleryApp
import com.amresalehin.emregallery.ui.IncomingLinkRequest
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme

class MainActivity : FragmentActivity() {

    private val prefs by lazy {
        getSharedPreferences("window_refresh_rate_state", Context.MODE_PRIVATE)
    }

    private var incomingLinkRequest by mutableStateOf<IncomingLinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyHighRefreshRate()
        warmupChromiumEngine()
        handleIncomingIntent(intent)

        setContent {
            val preferences = remember { PreferencesRepository(applicationContext) }
            val initialTheme = remember { preferences.getThemeSync() }
            val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = initialTheme)
            EmreGalleryTheme(theme) {
                GalleryApp(
                    incomingLinkRequest = incomingLinkRequest,
                    onConsumeIncomingLink = { incomingLinkRequest = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_VIEW -> {
                val rawUrl = intent.dataString?.trim().orEmpty()
                val extracted = extractHttpUrl(rawUrl)
                if (extracted != null) {
                    val subjectTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                    incomingLinkRequest = IncomingLinkRequest(
                        id = System.nanoTime(),
                        url = extracted,
                        title = subjectTitle
                    )
                }
            }
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                val extracted = extractHttpUrl(sharedText) ?: extractHttpUrl(sharedSubject)
                if (extracted != null) {
                    val titleHint = sharedSubject.takeIf { it.isNotBlank() && it != extracted }
                        ?: deriveTitleFromSharedText(sharedText, extracted)
                    incomingLinkRequest = IncomingLinkRequest(
                        id = System.nanoTime(),
                        url = extracted,
                        title = titleHint
                    )
                }
            }
        }
    }

    private fun extractHttpUrl(rawText: String): String? {
        if (rawText.isBlank()) return null
        val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        val match = urlRegex.find(rawText)?.value
            ?.trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')
        if (!match.isNullOrBlank() && MediaClassifier.isValidWebUrl(match)) {
            return match
        }
        val singleToken = rawText.trim().takeIf { !it.contains(' ') && it.contains('.') }
        if (singleToken != null) {
            val formatted = MediaClassifier.formatQueryOrUrl(singleToken)
            if (MediaClassifier.isValidWebUrl(formatted)) {
                return formatted
            }
        }
        return null
    }

    private fun deriveTitleFromSharedText(sharedText: String, extractedUrl: String): String {
        val cleaned = sharedText.replace(extractedUrl, "").trim()
        return cleaned.trimEnd('-', ':', '|', ' ').trim().take(120)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply and persist high refresh rate state when returning from pause/background
        applyHighRefreshRate()
        prefs.edit().putBoolean("is_paused", false).apply()
    }

    override fun onPause() {
        // Persist pause state
        prefs.edit().putBoolean("is_paused", true).putLong("paused_at", System.currentTimeMillis()).apply()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyHighRefreshRate()
        }
    }

    /**
     * Requests continuous 120Hz / 120 FPS for the app window across all supported Android display APIs.
     * Ensures Emre Gallery renders at maximum fluidity without artificial frame rate capping.
     */
    private fun applyHighRefreshRate() {
        val targetFps = 120f
        val currentWindow = this.window ?: return

        // Android 6.0+ (API 23+) WindowManager.LayoutParams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val params = currentWindow.attributes
            params.preferredRefreshRate = targetFps

            // Android 15+ (API 35) frame rate boost on touch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                params.setFrameRateBoostOnTouchEnabled(true)
            }

            // Select physical display mode >= 119Hz if hardware supports it
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }

            val modes = currentDisplay?.supportedModes ?: emptyArray()
            val bestHighRateMode = modes.filter { it.refreshRate >= 119f }
                .maxByOrNull { it.refreshRate }
                ?: modes.maxByOrNull { it.refreshRate }

            if (bestHighRateMode != null && bestHighRateMode.refreshRate >= 60f) {
                params.preferredDisplayModeId = bestHighRateMode.modeId
            }

            currentWindow.attributes = params
        }

        prefs.edit().putFloat("configured_refresh_rate", targetFps).apply()
    }

    private fun warmupChromiumEngine() {
        window?.decorView?.post {
            GalleryApplication.warmupChromium(this)
        }
    }
}


