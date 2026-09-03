package com.example.downloader.ffmpeg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.DownloadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FFmpegCancellationTest {

    private lateinit var context: Context
    private lateinit var ffmpegManager: FFmpegManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ffmpegManager = FFmpegManager(context)
    }

    @Test
    fun testCancelBeforeStart() = runBlocking {
        val taskId = "task_pre_cancel"
        val runId = 1
        val tempOutput = File(context.cacheDir, "test_pre_cancel.tmp").apply {
            writeText("intermediate data")
        }

        // Cancel arrives before execution starts
        ffmpegManager.cancel(taskId, runId)
        assertTrue("Task should be registered as cancelled", ffmpegManager.isTaskCancelled(taskId, runId))

        // Attempting to execute with the cancelled (taskId, runId) must fail immediately without running
        val result = ffmpegManager.executeFFmpeg(
            taskId = taskId,
            runId = runId,
            arguments = listOf("sh", "-c", "sleep 1"),
            tempOutput = tempOutput,
            totalDurationSeconds = 10.0,
            onProgress = null
        )

        assertTrue("Result should be a failure", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be DownloadError.Cancelled", error is DownloadError.Cancelled)
        assertFalse("Temp output file should be deleted on cancellation", tempOutput.exists())
        assertFalse("Execution should not be active", ffmpegManager.isExecutionActive(taskId, runId))
    }

    @Test
    fun testCancelDuringExecution() = runBlocking {
        val taskId = "task_active_cancel"
        val runId = 1
        val tempOutput = File(context.cacheDir, "test_active_cancel.tmp").apply {
            writeText("processing...")
        }

        // Launch long-running process in background
        val deferred = async(Dispatchers.IO) {
            ffmpegManager.executeFFmpeg(
                taskId = taskId,
                runId = runId,
                arguments = listOf("sh", "-c", "sleep 10"),
                tempOutput = tempOutput,
                totalDurationSeconds = 10.0,
                onProgress = null
            )
        }

        // Wait until execution is registered and active
        var active = false
        for (i in 0 until 50) {
            if (ffmpegManager.isExecutionActive(taskId, runId)) {
                active = true
                break
            }
            delay(20)
        }
        assertTrue("Execution should become active", active)

        // Cancel during execution
        ffmpegManager.cancel(taskId, runId)

        val result = withTimeoutOrNull(3000) { deferred.await() }
        assertNotNull("Deferred should complete promptly after cancel", result)
        assertTrue("Result should be failure", result!!.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be DownloadError.Cancelled", error is DownloadError.Cancelled)

        assertFalse("Active executions should no longer contain this task", ffmpegManager.isExecutionActive(taskId, runId))
        assertFalse("Temp output must be deleted", tempOutput.exists())
    }

    @Test
    fun testCancelAfterCompletion() = runBlocking {
        val taskId = "task_completed"
        val runId = 1
        val tempOutput = File(context.cacheDir, "test_completed.tmp")

        // Execute quick task
        val result = ffmpegManager.executeFFmpeg(
            taskId = taskId,
            runId = runId,
            arguments = listOf("echo", "done"),
            tempOutput = tempOutput,
            totalDurationSeconds = 0.0,
            onProgress = null
        )

        assertTrue("Execution should succeed", result.isSuccess)
        assertFalse("Execution must no longer be active", ffmpegManager.isExecutionActive(taskId, runId))
        assertEquals(0, ffmpegManager.getActiveExecutionCount())

        // Cancel after completion should be a safe no-op without errors
        ffmpegManager.cancel(taskId, runId)
        assertEquals(0, ffmpegManager.getActiveExecutionCount())
    }

    @Test
    fun testRetryThenCancelOldRunDoesNotAffectNewRun() = runBlocking {
        val taskId = "task_retry_lifecycle"
        val oldRunId = 1
        val newRunId = 2

        val tempOutputOld = File(context.cacheDir, "test_old_run.tmp").apply { writeText("old") }
        val tempOutputNew = File(context.cacheDir, "test_new_run.tmp").apply { writeText("new") }

        // Start old run (runId = 1)
        val oldDeferred = async(Dispatchers.IO) {
            ffmpegManager.executeFFmpeg(
                taskId = taskId,
                runId = oldRunId,
                arguments = listOf("sh", "-c", "sleep 10"),
                tempOutput = tempOutputOld,
                totalDurationSeconds = 10.0,
                onProgress = null
            )
        }

        // Wait for old run to become active
        for (i in 0 until 50) {
            if (ffmpegManager.isExecutionActive(taskId, oldRunId)) break
            delay(20)
        }
        assertTrue("Old run should be active", ffmpegManager.isExecutionActive(taskId, oldRunId))

        // Start new retry run (runId = 2)
        val newDeferred = async(Dispatchers.IO) {
            ffmpegManager.executeFFmpeg(
                taskId = taskId,
                runId = newRunId,
                arguments = listOf("sh", "-c", "sleep 10"),
                tempOutput = tempOutputNew,
                totalDurationSeconds = 10.0,
                onProgress = null
            )
        }

        // Wait for new run to become active
        for (i in 0 until 50) {
            if (ffmpegManager.isExecutionActive(taskId, newRunId)) break
            delay(20)
        }
        assertTrue("New run should be active", ffmpegManager.isExecutionActive(taskId, newRunId))

        // Cancel ONLY the old run (runId = 1)
        ffmpegManager.cancel(taskId, oldRunId)

        val oldResult = withTimeoutOrNull(3000) { oldDeferred.await() }
        assertNotNull("Old run should complete after cancellation", oldResult)
        assertTrue("Old run should be cancelled", oldResult!!.isFailure)
        assertFalse("Old run execution should no longer be active", ffmpegManager.isExecutionActive(taskId, oldRunId))
        assertFalse("Old temp file should be deleted", tempOutputOld.exists())

        // Critical requirement 5: Old Run cancellation must NOT affect New Run
        assertTrue("New run must still be active and unaffected by old run cancellation", ffmpegManager.isExecutionActive(taskId, newRunId))
        assertFalse("New run must not be marked cancelled", ffmpegManager.isTaskCancelled(taskId, newRunId))

        // Clean up new run
        ffmpegManager.cancel(taskId, newRunId)
        val newResult = withTimeoutOrNull(3000) { newDeferred.await() }
        assertNotNull("New run should complete after its own cancellation", newResult)
        assertFalse("New run execution should now be inactive", ffmpegManager.isExecutionActive(taskId, newRunId))
        assertFalse("New temp file should be deleted", tempOutputNew.exists())
    }
}
