package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DownloadActivityItem
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaNormalizer
import com.amresalehin.emregallery.data.ScrapedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketQueueDeduplicationTest {

    @Test
    fun previouslyDiscoveredBookmarkMediaIsNotRequeuedWithoutLocalCopy() {
        val existingBookmarkItem = MediaInboxItem(
            id = "bookmark-video",
            title = "Video",
            localUri = "",
            sourceUrl = "https://cdn.example.com/video.mp4?utm_source=bookmark",
            isVideo = true
        )
        val rediscovered = ScrapedMediaItem(
            id = "rediscovered-video",
            url = "https://cdn.example.com/video.mp4?utm_campaign=refresh",
            title = "Video",
            isVideo = true
        )
        val genuinelyNew = ScrapedMediaItem(
            id = "new-video",
            url = "https://cdn.example.com/new-video.mp4",
            title = "New video",
            isVideo = true
        )

        val result = MediaNormalizer.filterNewQueueCandidates(
            candidates = listOf(rediscovered, genuinelyNew),
            existingBookmarkItems = listOf(existingBookmarkItem),
            downloadActivities = emptyList()
        )

        assertEquals(listOf("new-video"), result.map { it.id })
    }

    @Test
    fun activeAndFinishedDownloadsAreNotQueuedAgain() {
        val activities = listOf(
            DownloadActivityItem(
                id = "queued-video",
                title = "Queued video",
                mediaUrl = "https://cdn.example.com/queued.mp4",
                status = DownloadActivityStatus.DOWNLOADING
            ),
            DownloadActivityItem(
                id = "finished-video",
                title = "Finished video",
                mediaUrl = "https://cdn.example.com/finished.mp4",
                status = DownloadActivityStatus.FINISHED
            ),
            DownloadActivityItem(
                id = "paused-video",
                title = "Paused video",
                mediaUrl = "https://cdn.example.com/paused.mp4",
                status = DownloadActivityStatus.PAUSED
            ),
            DownloadActivityItem(
                id = "retrying-video",
                title = "Retrying video",
                mediaUrl = "https://cdn.example.com/retrying.mp4",
                status = DownloadActivityStatus.RETRYING
            )
        )
        val candidates = activities.map { activity ->
            ScrapedMediaItem(
                id = "rediscovered-${activity.id}",
                url = activity.mediaUrl,
                title = activity.title,
                isVideo = true
            )
        } + ScrapedMediaItem(
            id = "new-video",
            url = "https://cdn.example.com/new.mp4",
            title = "New video",
            isVideo = true
        )

        val result = MediaNormalizer.filterNewQueueCandidates(
            candidates = candidates,
            existingBookmarkItems = emptyList(),
            downloadActivities = activities
        )

        assertEquals(listOf("new-video"), result.map { it.id })
    }

    @Test
    fun failedDownloadsRemainEligibleForRetry() {
        val failedActivity = DownloadActivityItem(
            id = "failed-video",
            title = "Failed video",
            mediaUrl = "https://cdn.example.com/failed.mp4",
            status = DownloadActivityStatus.FAILED
        )
        val candidate = ScrapedMediaItem(
            id = "retry-candidate",
            url = failedActivity.mediaUrl,
            title = "Failed video",
            isVideo = true
        )

        val result = MediaNormalizer.filterNewQueueCandidates(
            candidates = listOf(candidate),
            existingBookmarkItems = emptyList(),
            downloadActivities = listOf(failedActivity)
        )

        assertEquals(listOf("retry-candidate"), result.map { it.id })
    }

    @Test
    fun duplicateCandidatesWithinOneDiscoveryBatchAreCollapsed() {
        val first = ScrapedMediaItem(
            id = "first",
            url = "https://cdn.example.com/video.mp4?utm_source=one",
            title = "Video",
            isVideo = true
        )
        val second = ScrapedMediaItem(
            id = "second",
            url = "https://cdn.example.com/video.mp4?utm_campaign=two",
            title = "Video",
            isVideo = true
        )

        val result = MediaNormalizer.filterNewQueueCandidates(
            candidates = listOf(first, second),
            existingBookmarkItems = emptyList(),
            downloadActivities = emptyList()
        )

        assertEquals(1, result.size)
    }
}
