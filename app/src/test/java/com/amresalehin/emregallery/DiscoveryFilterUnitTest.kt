package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DiscoveryFilterManager
import com.amresalehin.emregallery.data.DiscoveryFilterState
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.ScrapedMediaItem
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFilterUnitTest {

    @Test
    fun defaultFilterRequirementsSatisfied() {
        val defaultState = DiscoveryFilterState()

        // Minimum width 480 px
        assertEquals(480, defaultState.minWidth)

        // Minimum height 540 px
        assertEquals(540, defaultState.minHeight)

        // Minimum size 10 KB
        assertEquals(10 * 1024L, defaultState.minSizeBytes)

        // SVG & PNG disabled by default
        assertFalse("PNG must be disabled by default", defaultState.enabledFormats.contains("PNG"))
        assertFalse("SVG must be disabled by default", defaultState.enabledFormats.contains("SVG"))

        // Standard formats enabled by default
        assertTrue(defaultState.enabledFormats.contains("JPG"))
        assertTrue(defaultState.enabledFormats.contains("WEBP"))
        assertTrue(defaultState.enabledFormats.contains("MP4"))
        assertTrue(defaultState.enabledFormats.contains("WEBM"))
        assertTrue(defaultState.enabledFormats.contains("M3U8"))
        assertTrue(defaultState.enabledFormats.contains("MP3"))
    }

    @Test
    fun pngAndSvgFilteredOutByDefault() {
        val defaultState = DiscoveryFilterState()

        val pngItem = ScrapedMediaItem(
            id = "png1",
            url = "https://example.com/banner.png",
            title = "Banner",
            format = "PNG",
            mimeType = "image/png",
            width = 1200,
            height = 800,
            sizeBytes = 100_000L
        )

        val svgItem = ScrapedMediaItem(
            id = "svg1",
            url = "https://example.com/illustration.svg",
            title = "Vector Artwork",
            format = "SVG",
            mimeType = "image/svg+xml",
            width = 1200,
            height = 800,
            sizeBytes = 50_000L
        )

        assertFalse("PNG should not match default filters", DiscoveryFilterManager.matchesFilters(pngItem, defaultState))
        assertFalse("SVG should not match default filters", DiscoveryFilterManager.matchesFilters(svgItem, defaultState))
    }

    @Test
    fun jpgAndWebpWithSufficientDimensionsAllowedByDefault() {
        val defaultState = DiscoveryFilterState()

        val jpgItem = ScrapedMediaItem(
            id = "jpg1",
            url = "https://example.com/photo.jpg",
            title = "Nature",
            format = "JPEG",
            mimeType = "image/jpeg",
            width = 1080,
            height = 1920,
            sizeBytes = 250_000L
        )

        val webpItem = ScrapedMediaItem(
            id = "webp1",
            url = "https://example.com/art.webp",
            title = "Painting",
            format = "WEBP",
            mimeType = "image/webp",
            width = 600,
            height = 600,
            sizeBytes = 80_000L
        )

        assertTrue("Large JPG must pass default filters", DiscoveryFilterManager.matchesFilters(jpgItem, defaultState))
        assertTrue("Large WEBP must pass default filters", DiscoveryFilterManager.matchesFilters(webpItem, defaultState))
    }

    @Test
    fun dimensionFilterRejectsSubDimensionMedia() {
        val defaultState = DiscoveryFilterState() // minWidth = 480, minHeight = 540

        // Width 300 < 480
        val narrowItem = ScrapedMediaItem(
            id = "narrow",
            url = "https://example.com/skinny.jpg",
            title = "Narrow",
            format = "JPG",
            width = 300,
            height = 800,
            sizeBytes = 50_000L
        )
        assertFalse("Width < 480 should be filtered out", DiscoveryFilterManager.matchesFilters(narrowItem, defaultState))

        // Height 400 < 540
        val shortItem = ScrapedMediaItem(
            id = "short",
            url = "https://example.com/short.jpg",
            title = "Short",
            format = "JPG",
            width = 800,
            height = 400,
            sizeBytes = 50_000L
        )
        assertFalse("Height < 540 should be filtered out", DiscoveryFilterManager.matchesFilters(shortItem, defaultState))
    }

    @Test
    fun minSizeFilterRejectsSubSizeMedia() {
        val defaultState = DiscoveryFilterState() // minSize = 10KB (10240 bytes)

        val tinyItem = ScrapedMediaItem(
            id = "tiny",
            url = "https://example.com/tiny.jpg",
            title = "Tiny",
            format = "JPG",
            width = 800,
            height = 600,
            sizeBytes = 4096L // 4 KB < 10 KB
        )

        assertFalse("Size < 10KB should be filtered out", DiscoveryFilterManager.matchesFilters(tinyItem, defaultState))

        val substantialItem = ScrapedMediaItem(
            id = "substantial",
            url = "https://example.com/good.jpg",
            title = "Good",
            format = "JPG",
            width = 800,
            height = 600,
            sizeBytes = 25_000L // 25 KB > 10 KB
        )

        assertTrue("Size > 10KB should pass", DiscoveryFilterManager.matchesFilters(substantialItem, defaultState))
    }

    @Test
    fun userCanChangeFiltersLater() {
        // User enables PNG and reduces minimum width and height to 100 px
        val userModified = DiscoveryFilterState(
            minWidth = 100,
            minHeight = 100,
            minSizeBytes = 0L,
            enabledFormats = DiscoveryFilterState.DEFAULT_ENABLED_FORMATS + "PNG" + "SVG"
        )

        val smallPng = ScrapedMediaItem(
            id = "png_small",
            url = "https://example.com/icon.png",
            title = "Icon",
            format = "PNG",
            width = 120,
            height = 120,
            sizeBytes = 5_000L
        )

        assertTrue("User customized filter should allow PNG", DiscoveryFilterManager.matchesFilters(smallPng, userModified))
    }

    @Test
    fun immediateStateFlowUpdatesOnFilterChange() {
        val initial = DiscoveryFilterManager.filterState.value
        val updated = initial.copy(minWidth = 320, minHeight = 400)
        DiscoveryFilterManager.updateFilters(updated)
        assertEquals(320, DiscoveryFilterManager.filterState.value.minWidth)
        assertEquals(400, DiscoveryFilterManager.filterState.value.minHeight)
        DiscoveryFilterManager.resetToDefaults()
        assertEquals(480, DiscoveryFilterManager.filterState.value.minWidth)
        assertEquals(540, DiscoveryFilterManager.filterState.value.minHeight)
    }
}
