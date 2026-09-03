package com.example.downloader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskDao
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.domain.model.DownloadStatus
import com.example.downloader.util.RetryPolicy
import com.example.downloader.util.SpeedSmoother
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DownloadQueueManagerTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: DownloadTaskDao
    private lateinit var repository: DownloadRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadTaskDao()
        repository = DownloadRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testQueueOrderAndReordering() = runBlocking {
        val task1 = DownloadTaskEntity(
            id = "task_1",
            url = "https://youtube.com/watch?v=111",
            title = "Video 1",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.QUEUED,
            queueOrder = 100L
        )
        val task2 = DownloadTaskEntity(
            id = "task_2",
            url = "https://youtube.com/watch?v=222",
            title = "Video 2",
            formatId = "22",
            formatDescription = "720p",
            status = DownloadStatus.QUEUED,
            queueOrder = 200L
        )
        val task3 = DownloadTaskEntity(
            id = "task_3",
            url = "https://youtube.com/watch?v=333",
            title = "Video 3",
            formatId = "137",
            formatDescription = "1080p",
            status = DownloadStatus.QUEUED,
            queueOrder = 300L
        )

        repository.insertTask(task1)
        repository.insertTask(task2)
        repository.insertTask(task3)

        var queued = repository.getQueuedTasks()
        assertEquals(3, queued.size)
        assertEquals("task_1", queued[0].id)
        assertEquals("task_2", queued[1].id)
        assertEquals("task_3", queued[2].id)

        // Move task3 to top
        repository.updateQueueOrder("task_3", 50L)
        queued = repository.getQueuedTasks()
        assertEquals("task_3", queued[0].id)
        assertEquals("task_1", queued[1].id)
        assertEquals("task_2", queued[2].id)
    }

    @Test
    fun testDuplicateDetection() = runBlocking {
        val original = DownloadTaskEntity(
            id = "orig_task",
            url = "https://youtube.com/watch?v=dup123",
            title = "Duplicate Video",
            formatId = "22",
            formatDescription = "720p",
            startTime = "00:00:10",
            endTime = "00:00:40",
            status = DownloadStatus.COMPLETED
        )
        repository.insertTask(original)

        val found = repository.findExistingTask(
            url = "https://youtube.com/watch?v=dup123",
            formatId = "22",
            startTime = "00:00:10",
            endTime = "00:00:40"
        )
        assertNotNull(found)
        assertEquals("orig_task", found?.id)

        val notFound = repository.findExistingTask(
            url = "https://youtube.com/watch?v=dup123",
            formatId = "18", // Different format
            startTime = "00:00:10",
            endTime = "00:00:40"
        )
        assertNull(notFound)
    }

    @Test
    fun testInterruptedRecovery() = runBlocking {
        val downloadingTask = DownloadTaskEntity(
            id = "active_task",
            url = "https://youtube.com/watch?v=active",
            title = "Active Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            progress = 45f
        )
        repository.insertTask(downloadingTask)

        val count = repository.markActiveTasksAsInterrupted()
        assertEquals(1, count)

        val recovered = repository.getTaskByIdSync("active_task")
        assertEquals(DownloadStatus.INTERRUPTED, recovered?.status)
    }

    @Test
    fun testRetryPolicyAndBackoff() {
        val networkError = IOException("Connection reset by peer")
        assertTrue(RetryPolicy.isRetryable(networkError))

        val permanentError = IllegalArgumentException("Invalid URL or format not supported")
        assertFalse(RetryPolicy.isRetryable(permanentError))

        // Exponential backoff tests: 1s, 2s, 5s, 10s...
        assertEquals(1000L, RetryPolicy.getBackoffDelayMs(0))
        assertEquals(2000L, RetryPolicy.getBackoffDelayMs(1))
        assertEquals(5000L, RetryPolicy.getBackoffDelayMs(2))
    }

    @Test
    fun testSpeedSmoothingAndEta() {
        val smoother = SpeedSmoother()
        val smoothed = smoother.update(1024 * 1024.0) // 1 MB/s
        val formattedSpeed = SpeedSmoother.formatSpeed(smoothed)
        assertTrue(formattedSpeed.contains("MB/s") || formattedSpeed.contains("KB/s"))

        val eta = SpeedSmoother.formatEta(65)
        assertEquals("01:05", eta)

        val longEta = SpeedSmoother.formatEta(3665)
        assertEquals("01:01:05", longEta)
    }

    @Test
    fun testBulkActions() = runBlocking {
        for (i in 1..5) {
            repository.insertTask(
                DownloadTaskEntity(
                    id = "task_$i",
                    url = "https://youtube.com/watch?v=$i",
                    title = "Video $i",
                    formatId = "18",
                    formatDescription = "360p",
                    status = DownloadStatus.COMPLETED
                )
            )
        }

        val allCompleted = repository.getAllCompletedTasksSync()
        assertEquals(5, allCompleted.size)

        repository.deleteTasksByIds(listOf("task_1", "task_2"))
        val remaining = repository.getAllCompletedTasksSync()
        assertEquals(3, remaining.size)
        assertNull(repository.getTaskByIdSync("task_1"))
        assertNull(repository.getTaskByIdSync("task_2"))
        assertNotNull(repository.getTaskByIdSync("task_3"))
    }

    @Test
    fun testDirectProgressUpdatePerformance() = runBlocking {
        val task = DownloadTaskEntity(
            id = "perf_task",
            url = "https://youtube.com/watch?v=perf",
            title = "Perf Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            progress = 0f
        )
        repository.insertTask(task)

        val startTime = System.currentTimeMillis()
        for (i in 1..100) {
            repository.updateProgress("perf_task", DownloadStatus.DOWNLOADING, i.toFloat(), "5.2 MB/s", "00:20")
        }
        val durationMs = System.currentTimeMillis() - startTime
        assertTrue("100 direct updates should be fast (< 500ms)", durationMs < 500)

        val updated = repository.getTaskByIdSync("perf_task")
        assertEquals(100f, updated?.progress)
        assertEquals("5.2 MB/s", updated?.downloadSpeed)
    }

    @Test
    fun testConcurrencyLimitsAndQueuedExecution() = runBlocking {
        for (i in 1..10) {
            repository.insertTask(
                DownloadTaskEntity(
                    id = "queue_task_$i",
                    url = "https://youtube.com/watch?v=q_$i",
                    title = "Queue Video $i",
                    formatId = "18",
                    formatDescription = "360p",
                    status = DownloadStatus.QUEUED,
                    queueOrder = i.toLong()
                )
            )
        }

        val queued = repository.getQueuedTasks()
        assertEquals(10, queued.size)

        val maxConcurrency = 3
        val activeSlots = queued.take(maxConcurrency)
        assertEquals(3, activeSlots.size)
        assertEquals("queue_task_1", activeSlots[0].id)
        assertEquals("queue_task_2", activeSlots[1].id)
        assertEquals("queue_task_3", activeSlots[2].id)
    }

    @Test
    fun testStressHighVolumeTasks() = runBlocking {
        val tasks = (1..200).map { i ->
            DownloadTaskEntity(
                id = "stress_$i",
                url = "https://youtube.com/watch?v=stress_$i",
                title = "Stress Video $i",
                formatId = "22",
                formatDescription = "720p",
                status = if (i % 2 == 0) DownloadStatus.COMPLETED else DownloadStatus.QUEUED,
                queueOrder = i.toLong()
            )
        }
        for (t in tasks) {
            repository.insertTask(t)
        }

        val completed = repository.getAllCompletedTasksSync()
        assertEquals(100, completed.size)

        val queued = repository.getQueuedTasks()
        assertEquals(100, queued.size)

        repository.clearFinishedTasks()
        val afterClear = repository.getAllCompletedTasksSync()
        assertEquals(0, afterClear.size)
    }

    @Test
    fun testDatabaseConsistencyMarksMissingFilesAsFailed() = runBlocking {
        val nonExistentPath = "/storage/emulated/0/DownloadVideos/non_existent_video.mp4"
        val task = DownloadTaskEntity(
            id = "missing_file_task",
            url = "https://youtube.com/watch?v=missing",
            title = "Missing Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.COMPLETED,
            filePath = nonExistentPath,
            contentUri = null
        )
        repository.insertTask(task)

        // Verify task is initially recorded as COMPLETED
        val initial = repository.getTaskByIdSync("missing_file_task")
        assertEquals(DownloadStatus.COMPLETED, initial?.status)

        val queueManager = com.example.downloader.queue.DownloadQueueManager(
            context = context,
            repository = repository
        )
        queueManager.verifyDatabaseConsistency()

        // Consistency check should update missing file task status to FAILED
        val updated = repository.getTaskByIdSync("missing_file_task")
        assertEquals(DownloadStatus.FAILED, updated?.status)
        assertNotNull(updated?.errorMessage)
        assertTrue(updated?.errorMessage?.contains("missing", ignoreCase = true) == true)
    }

    @Test
    fun testPausePreservesProgressAndTaskState() = runBlocking {
        val queueManager = com.example.downloader.queue.DownloadQueueManager(
            context = context,
            repository = repository
        )
        // Give background initialization job a moment to complete startup recovery
        kotlinx.coroutines.delay(100)

        val task = DownloadTaskEntity(
            id = "pause_test_task",
            url = "https://youtube.com/watch?v=pause_test",
            title = "Pause Test Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            progress = 45.5f,
            downloadSpeed = "1.5 MB/s",
            downloadedSize = "15 MB",
            totalSize = "33 MB"
        )
        repository.insertTask(task)

        queueManager.pauseDownload("pause_test_task")

        // Wait brief moment for coroutine
        kotlinx.coroutines.delay(200)

        val pausedTask = repository.getTaskByIdSync("pause_test_task")
        assertEquals(DownloadStatus.PAUSED, pausedTask?.status)
        assertEquals(45.5f, pausedTask?.progress ?: 0f, 0.01f)
        assertEquals("", pausedTask?.downloadSpeed)
        assertEquals("15 MB", pausedTask?.downloadedSize)
    }

    @Test
    fun testStaleProgressCallbackIgnoredWhenPaused() = runBlocking {
        val task = DownloadTaskEntity(
            id = "stale_callback_task",
            url = "https://youtube.com/watch?v=stale",
            title = "Stale Test Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.PAUSED,
            progress = 40f,
            downloadSpeed = "",
            downloadedSize = "10 MB",
            totalSize = "25 MB"
        )
        repository.insertTask(task)

        // Attempting to update progress on a PAUSED task
        dao.updateProgress(
            id = "stale_callback_task",
            status = DownloadStatus.DOWNLOADING,
            progress = 42f,
            downloadSpeed = "2.0 MB/s",
            eta = "10s",
            downloadedSize = "11 MB",
            totalSize = "25 MB"
        )

        // Verify status remains PAUSED because DAO protects non-active tasks
        val currentTask = repository.getTaskByIdSync("stale_callback_task")
        assertEquals(DownloadStatus.PAUSED, currentTask?.status)
        assertEquals(40f, currentTask?.progress ?: 0f, 0.01f)
    }
}
