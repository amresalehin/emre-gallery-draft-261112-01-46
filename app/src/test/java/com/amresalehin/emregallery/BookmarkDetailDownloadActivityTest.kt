package com.amresalehin.emregallery

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.PocketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkDetailDownloadActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DownloadActivityManager.initialize(context)
        DownloadActivityManager.clearFinished()
        context.getSharedPreferences("pocket_bookmarks_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("download_activity_tracker_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testBookmarkMediaDownloadRegistersAndFinishesInDownloadActivityManager() {
        val stackId = "stack_bookmark_101"
        val itemId = "item_media_202"
        val mediaUrl = "https://example.com/art.jpg"
        val title = "Digital Artwork"

        // 1. Initial queuing from bookmark detail screen
        DownloadActivityManager.recordQueued(
            id = itemId,
            title = title,
            mediaUrl = mediaUrl,
            thumbnailUrl = mediaUrl,
            isVideo = false,
            isAudio = false,
            isStream = false,
            format = "JPG",
            stackId = stackId,
            stackTitle = "Art Showcase",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/art"
        )

        val queuedList = DownloadActivityManager.activities.value
        val queuedItem = queuedList.firstOrNull { it.id == itemId }
        assertNotNull("Item must be recorded in DownloadActivityManager when download starts", queuedItem)
        assertEquals(DownloadActivityStatus.DOWNLOADING, queuedItem?.status)
        assertEquals(title, queuedItem?.title)
        assertEquals(stackId, queuedItem?.stackId)

        // 2. Mark download finished (e.g. file saved to device)
        val dummySavedFile = File(context.cacheDir, "saved_art.jpg").apply {
            writeText("dummy-image-bytes")
        }
        val fileUri = Uri.fromFile(dummySavedFile).toString()

        DownloadActivityManager.markFinished(
            itemId = itemId,
            localUri = fileUri,
            sizeBytes = dummySavedFile.length()
        )

        val finishedList = DownloadActivityManager.activities.value
        val finishedItem = finishedList.firstOrNull { it.id == itemId }
        assertNotNull("Finished download must remain in activities", finishedItem)
        assertEquals(DownloadActivityStatus.FINISHED, finishedItem?.status)
        assertEquals(fileUri, finishedItem?.localUri)
        assertEquals(1.0f, finishedItem?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testSyncWithInboxRepositoryIncludesPocketBookmarksWithLocalMedia() {
        val pocketRepo = PocketRepository.getInstance(context)

        // Create a local dummy file representing a previously downloaded media item from bookmark detail
        val localMediaFile = File(context.filesDir, "downloaded_photo.jpg").apply {
            writeText("sample-photo-data")
        }
        val localMediaUri = Uri.fromFile(localMediaFile).toString()

        val createdBookmark = pocketRepo.addBookmark(
            title = "Tech Blog",
            url = "https://tech.blog/post/1",
            domain = "tech.blog",
            description = "",
            autoFetchMetadata = false
        )

        pocketRepo.updateBookmark(
            createdBookmark.copy(
                items = listOf(
                    MediaInboxItem(
                        id = "tech_photo_1",
                        title = "Diagram",
                        localUri = localMediaUri,
                        sourceUrl = "https://tech.blog/diagram.jpg",
                        isVideo = false,
                        format = "JPG",
                        sizeBytes = localMediaFile.length()
                    )
                )
            )
        )

        // Trigger syncWithInboxRepository on DownloadActivityManager
        DownloadActivityManager.syncWithInboxRepository()

        val activities = DownloadActivityManager.activities.value
        val syncedItem = activities.firstOrNull { it.localUri == localMediaUri }
        assertNotNull("Activity page must show media downloaded from bookmark detail via syncWithInboxRepository", syncedItem)
        assertEquals(DownloadActivityStatus.FINISHED, syncedItem?.status)
        assertEquals("Diagram", syncedItem?.title)
        assertEquals(createdBookmark.id, syncedItem?.stackId)
    }

    @Test
    fun testSyncWithStorageDirRecursivelyFindsDomainSubdirectories() {
        val baseStorageDir = OkHttpDownloader.getTargetStorageDir(context)
        val domainSubDir = File(baseStorageDir, "twitter_com").apply { mkdirs() }
        val nestedFile = File(domainSubDir, "tweet_video.mp4").apply {
            writeText("video_mp4_bytes")
        }

        DownloadActivityManager.syncWithStorageDir(context)

        val activities = DownloadActivityManager.activities.value
        val found = activities.firstOrNull { it.title == "tweet_video.mp4" }
        assertNotNull("Nested domain subdirectory file must be discovered by syncWithStorageDir", found)
        assertEquals("MP4", found?.format)
        assertTrue(found?.isVideo == true)
        assertEquals(DownloadActivityStatus.FINISHED, found?.status)
    }
}
