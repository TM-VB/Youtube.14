package com.example.downloader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskDao
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus
import com.example.downloader.lifecycle.DownloadStateMachine
import com.example.downloader.queue.DownloadQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadLifecycleRaceConditionTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: DownloadTaskDao
    private lateinit var repository: DownloadRepository
    private lateinit var context: Context
    private lateinit var queueManager: DownloadQueueManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadTaskDao()
        repository = DownloadRepository(dao)
        queueManager = DownloadQueueManager(
            context = context,
            repository = repository
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testExecutionIdentityRejectsStaleRunId() = runBlocking {
        val taskId = "task_execution_identity"
        val oldRunId = 1001L
        val newRunId = 1002L

        // Initial task state under new run
        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=stale_run",
            title = "Test RunId Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = newRunId,
            progress = 50f
        )
        repository.insertTask(task)

        // Attempt to update progress using old runId
        val updatedRows = dao.updateProgress(
            id = taskId,
            runId = oldRunId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 75f,
            downloadSpeed = "5.0 MB/s",
            eta = "00:10"
        )

        assertEquals(0, updatedRows)
        val current = repository.getTaskByIdSync(taskId)
        assertNotNull(current)
        assertEquals(50f, current!!.progress, 0.01f)
        assertEquals(newRunId, current.runId)
    }

    @Test
    fun testStateMachineRejectsCallbackOnPausedTask() = runBlocking {
        val taskId = "task_paused_rejection"
        val runId = 2001L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=paused",
            title = "Paused Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.PAUSED,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            progress = 30f
        )
        repository.insertTask(task)

        assertFalse(DownloadStateMachine.canAcceptCallback(runId, runId, DownloadStatus.PAUSED))

        val updatedRows = dao.updateProgress(
            id = taskId,
            runId = runId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 35f,
            downloadSpeed = "1.2 MB/s",
            eta = "00:30"
        )

        assertEquals(0, updatedRows)
        val current = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.PAUSED, current?.status)
        assertEquals(30f, current?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testStateMachineRejectsCallbackOnCancelledTask() = runBlocking {
        val taskId = "task_cancelled_rejection"
        val runId = 3001L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=cancelled",
            title = "Cancelled Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.CANCELLED,
            stage = DownloadStage.QUEUED,
            runId = runId,
            progress = 20f
        )
        repository.insertTask(task)

        assertFalse(DownloadStateMachine.canAcceptCallback(runId, runId, DownloadStatus.CANCELLED))

        val updatedRows = dao.updateProgress(
            id = taskId,
            runId = runId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 25f,
            downloadSpeed = "2.0 MB/s",
            eta = "00:20"
        )

        assertEquals(0, updatedRows)
        val current = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.CANCELLED, current?.status)
    }

    @Test
    fun testStateMachineRejectsCallbackOnCompletedTask() = runBlocking {
        val taskId = "task_completed_rejection"
        val runId = 4001L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=completed",
            title = "Completed Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.COMPLETED,
            stage = DownloadStage.COMPLETED,
            runId = runId,
            progress = 100f
        )
        repository.insertTask(task)

        assertFalse(DownloadStateMachine.canAcceptCallback(runId, runId, DownloadStatus.COMPLETED))

        val updatedRows = dao.updateProgress(
            id = taskId,
            runId = runId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 40f,
            downloadSpeed = "1.0 MB/s",
            eta = "00:50"
        )

        assertEquals(0, updatedRows)
        val current = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.COMPLETED, current?.status)
        assertEquals(100f, current?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testRetryAllocatesNewRunIdAndResetsProgress() = runBlocking {
        val taskId = "task_retry_run_id"
        val initialRunId = 5001L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=retry_test",
            title = "Retry Test Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.FAILED,
            stage = DownloadStage.DOWNLOADING,
            runId = initialRunId,
            progress = 65f,
            retryCount = 0
        )
        repository.insertTask(task)

        queueManager.retryDownloadSync(taskId)

        val retriedTask = repository.getTaskByIdSync(taskId)
        assertNotNull(retriedTask)
        assertEquals(DownloadStatus.QUEUED, retriedTask!!.status)
        assertEquals(DownloadStage.QUEUED, retriedTask.stage)
        assertEquals(0f, retriedTask.progress, 0.01f)
        assertEquals(1, retriedTask.retryCount)
        assertNotEquals(initialRunId, retriedTask.runId)
        assertTrue(retriedTask.runId > initialRunId)
    }

    @Test
    fun testConcurrentDeleteAndProgressCallbacks() = runBlocking {
        val taskId = "task_race_delete"
        val runId = 6001L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=race_delete",
            title = "Race Delete Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            progress = 50f
        )
        repository.insertTask(task)

        // Launch concurrent racing operations: 30 callbacks vs 1 delete operation
        coroutineScope {
            val callbacks = (1..30).map { i ->
                async(Dispatchers.Default) {
                    dao.updateProgress(
                        id = taskId,
                        runId = runId,
                        status = DownloadStatus.DOWNLOADING,
                        stage = DownloadStage.DOWNLOADING,
                        progress = 50f + i,
                        downloadSpeed = "3 MB/s",
                        eta = "10s"
                    )
                }
            }

            val deleteJob = async(Dispatchers.Default) {
                queueManager.deleteDownloadSync(taskId)
            }

            callbacks.awaitAll()
            deleteJob.await()
        }

        // Verify task is completely gone from DB and cannot be revived
        val current = repository.getTaskByIdSync(taskId)
        assertNull(current)
    }

    @Test
    fun testStageTransitionValidation() {
        // Legal status transitions
        assertTrue(DownloadStateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.PREPARING))
        assertTrue(DownloadStateMachine.isValidTransition(DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING))
        assertTrue(DownloadStateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING_FFMPEG))
        assertTrue(DownloadStateMachine.isValidTransition(DownloadStatus.PROCESSING_FFMPEG, DownloadStatus.COMPLETED))
        assertTrue(DownloadStateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED))
        assertTrue(DownloadStateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED))

        // Illegal status transitions
        assertFalse(DownloadStateMachine.isValidTransition(DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadStateMachine.isValidTransition(DownloadStatus.CANCELLED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadStateMachine.isValidTransition(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadStateMachine.isValidTransition(DownloadStatus.FAILED, DownloadStatus.DOWNLOADING))

        // Legal stage transitions
        assertTrue(DownloadStateMachine.isValidStageTransition(DownloadStage.QUEUED, DownloadStage.PREPARING))
        assertTrue(DownloadStateMachine.isValidStageTransition(DownloadStage.PREPARING, DownloadStage.DOWNLOADING))
        assertTrue(DownloadStateMachine.isValidStageTransition(DownloadStage.DOWNLOADING, DownloadStage.MERGING))
        assertTrue(DownloadStateMachine.isValidStageTransition(DownloadStage.MERGING, DownloadStage.PUBLISHING))
        assertTrue(DownloadStateMachine.isValidStageTransition(DownloadStage.PUBLISHING, DownloadStage.COMPLETED))

        // Illegal stage jumps
        assertFalse(DownloadStateMachine.isValidStageTransition(DownloadStage.COMPLETED, DownloadStage.DOWNLOADING))
        assertFalse(DownloadStateMachine.isValidStageTransition(DownloadStage.PREPARING, DownloadStage.COMPLETED))
    }
}
