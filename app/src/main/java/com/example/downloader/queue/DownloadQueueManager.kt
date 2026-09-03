package com.example.downloader.queue

import android.content.Context
import android.net.Uri
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.data.settings.AppSettings
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus
import com.example.domain.model.TimeRange
import com.example.downloader.cleanup.CleanupManager
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.downloader.lifecycle.DownloadStateMachine
import com.example.downloader.network.NetworkMonitor
import com.example.downloader.util.RetryPolicy
import com.example.downloader.util.SpeedSmoother
import com.example.service.DownloadForegroundService
import com.example.storage.MediaStoreHelper
import com.example.storage.StorageSpaceChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Advanced Queue & Lifecycle Manager for media downloads.
 * Implements strict state machine validation and execution identity (runId).
 * Guarantees that:
 * 1. Every execution receives a unique runId.
 * 2. Start/resume/retry allocate a fresh runId.
 * 3. Callbacks from stale runs or invalid states are completely rejected.
 * 4. Operations (pause/cancel/delete/retry/resume) are serialized and safe per-task.
 * 5. DownloadStage transitions strictly follow QUEUED -> PREPARING -> DOWNLOADING -> MERGING/CUTTING -> PUBLISHING -> COMPLETED.
 */
class DownloadQueueManager(
    private val context: Context,
    private val repository: DownloadRepository = DownloadRepository(AppDatabase.getInstance(context).downloadTaskDao()),
    private val appSettings: AppSettings = AppSettings.getInstance(context),
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val downloadEngine: DownloadEngine = YtDlpDownloadEngine.getInstance(context),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val runIdCounter = AtomicLong(System.currentTimeMillis())
    private val activeRunIds = ConcurrentHashMap<String, Long>()
    private val taskMutexes = ConcurrentHashMap<String, Mutex>()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val speedSmoothers = ConcurrentHashMap<String, SpeedSmoother>()
    private val lastProgressUpdateTimes = ConcurrentHashMap<String, Long>()
    private val lastReportedProgress = ConcurrentHashMap<String, Float>()
    private val lastNotificationTimes = ConcurrentHashMap<String, Long>()
    private val queueMutex = Mutex()

    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    init {
        // Startup: Recover tasks that were abruptly interrupted by app restart or OS termination
        scope.launch {
            recoverInterruptedDownloads()
            verifyDatabaseConsistency()
        }

        // Network monitoring: Auto-resume queued downloads when connectivity returns
        scope.launch {
            networkMonitor.isOnlineFlow.collect { isOnline ->
                if (isOnline) {
                    processQueue()
                }
            }
        }
    }

    private fun generateRunId(): Long = runIdCounter.incrementAndGet()

    private fun getTaskMutex(taskId: String): Mutex = taskMutexes.getOrPut(taskId) { Mutex() }

    fun getActiveRunId(taskId: String): Long? = activeRunIds[taskId]

    suspend fun recoverInterruptedDownloads() {
        try {
            repository.markActiveTasksAsInterrupted()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun verifyDatabaseConsistency() {
        try {
            val completedTasks = repository.getAllCompletedTasksSync()
            for (task in completedTasks) {
                val path = task.filePath
                val uriStr = task.contentUri
                var exists = false

                if (!path.isNullOrBlank()) {
                    exists = File(path).exists()
                }
                if (!exists && !uriStr.isNullOrBlank()) {
                    try {
                        val uri = Uri.parse(uriStr)
                        context.contentResolver.openInputStream(uri)?.use {
                            exists = true
                        }
                    } catch (_: Exception) {
                        exists = false
                    }
                }

                if (!exists) {
                    repository.updateTask(
                        task.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = "File missing from disk or was moved."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun checkDuplicate(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity? {
        return repository.findExistingTask(url, formatId, startTime, endTime)
    }

    fun enqueueDownload(request: DownloadRequest): String {
        val taskId = request.id
        val entity = DownloadTaskEntity(
            id = taskId,
            url = request.url,
            title = request.title,
            thumbnailUrl = request.thumbnailUrl,
            formatId = request.formatSelector,
            formatDescription = request.formatDescription,
            startTime = request.startTime,
            endTime = request.endTime,
            cutMode = request.cutMode.id,
            status = DownloadStatus.QUEUED,
            stage = DownloadStage.QUEUED,
            runId = 0L,
            progress = 0f,
            isAudioOnly = request.isAudioOnly,
            isVideoOnly = request.isVideoOnly,
            downloadSubtitles = request.downloadSubtitles,
            subtitleLanguage = request.subtitleLanguage,
            queueOrder = System.currentTimeMillis()
        )

        scope.launch {
            repository.insertTask(entity)
            processQueue()
        }

        return taskId
    }

    fun enqueueDownload(
        url: String,
        title: String,
        thumbnailUrl: String?,
        formatId: String,
        formatDescription: String,
        isAudioOnly: Boolean,
        timeRange: TimeRange?,
        downloadSubtitles: Boolean = false,
        subtitleLanguage: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val entity = DownloadTaskEntity(
            id = taskId,
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            formatId = formatId,
            formatDescription = formatDescription,
            startTime = timeRange?.startTime,
            endTime = timeRange?.endTime,
            cutMode = timeRange?.cutMode?.id ?: "none",
            status = DownloadStatus.QUEUED,
            stage = DownloadStage.QUEUED,
            runId = 0L,
            progress = 0f,
            isAudioOnly = isAudioOnly,
            isVideoOnly = false,
            downloadSubtitles = downloadSubtitles,
            subtitleLanguage = subtitleLanguage,
            queueOrder = System.currentTimeMillis()
        )

        scope.launch {
            repository.insertTask(entity)
            processQueue()
        }

        return taskId
    }

    fun enqueueBatch(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val entities = requests.mapIndexed { idx, req ->
                DownloadTaskEntity(
                    id = req.id,
                    url = req.url,
                    title = req.title,
                    thumbnailUrl = req.thumbnailUrl,
                    formatId = req.formatSelector,
                    formatDescription = req.formatDescription,
                    startTime = req.startTime,
                    endTime = req.endTime,
                    cutMode = req.cutMode.id,
                    status = DownloadStatus.QUEUED,
                    stage = DownloadStage.QUEUED,
                    runId = 0L,
                    progress = 0f,
                    isAudioOnly = req.isAudioOnly,
                    isVideoOnly = req.isVideoOnly,
                    downloadSubtitles = req.downloadSubtitles,
                    subtitleLanguage = req.subtitleLanguage,
                    queueOrder = now + idx
                )
            }
            entities.forEach { repository.insertTask(it) }
            processQueue()
        }
    }

    private suspend fun cancelDownloadInternal(taskId: String, cleanupFiles: Boolean = true) {
        // Invalidate execution identity first
        activeRunIds.remove(taskId)
        try {
            downloadEngine.cancel(taskId)
        } catch (_: Throwable) {}
        val job = activeJobs.remove(taskId)
        job?.cancel()
        job?.join()
        speedSmoothers.remove(taskId)
        lastProgressUpdateTimes.remove(taskId)
        lastReportedProgress.remove(taskId)
        lastNotificationTimes.remove(taskId)
        _activeDownloadCount.value = activeJobs.size
        if (cleanupFiles) {
            CleanupManager.cleanupTaskFiles(context, taskId)
        }
    }

    suspend fun pauseDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // 1. Invalidate execution identity so in-flight callbacks are dropped immediately
            activeRunIds.remove(taskId)

            // 2. Stop running engines and await job completion
            cancelDownloadInternal(taskId, cleanupFiles = false)

            // 3. Persist PAUSED state
            val current = repository.getTaskByIdSync(taskId)
            if (current != null && !DownloadStateMachine.isTerminal(current.status)) {
                repository.updateTask(
                    current.copy(
                        status = DownloadStatus.PAUSED,
                        downloadSpeed = "",
                        eta = ""
                    )
                )
                DownloadForegroundService.updateOrDismissIfIdle(
                    context, taskId, current.title, DownloadStatus.PAUSED, current.progress.toInt(), ""
                )
            }

            processQueue()
        }
    }

    fun pauseDownload(taskId: String): Job = scope.launch {
        pauseDownloadSync(taskId)
    }

    suspend fun resumeDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // 1. Invalidate previous execution identity
            activeRunIds.remove(taskId)

            val current = repository.getTaskByIdSync(taskId) ?: return@withLock
            if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.INTERRUPTED) {
                // 2. Point 6: Rely on persisted stage, with file check only as fallback recovery
                val recoveredStage = when (current.stage) {
                    DownloadStage.MERGING, DownloadStage.CUTTING -> {
                        val taskWorkDir = File(context.cacheDir, "ytdlp_downloads/$taskId")
                        val hasMedia = taskWorkDir.listFiles()?.any {
                            it.isFile && it.length() > 1024L && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
                        } == true
                        if (hasMedia) current.stage else DownloadStage.QUEUED
                    }
                    else -> DownloadStage.QUEUED
                }

                val targetStatus = if (recoveredStage == DownloadStage.MERGING || recoveredStage == DownloadStage.CUTTING) {
                    DownloadStatus.PROCESSING_FFMPEG
                } else {
                    DownloadStatus.QUEUED
                }

                // 3. Start resume with fresh execution identity
                val newRunId = generateRunId()

                repository.updateTask(
                    current.copy(
                        status = targetStatus,
                        stage = recoveredStage,
                        runId = newRunId,
                        errorMessage = null,
                        queueOrder = System.currentTimeMillis()
                    )
                )
                processQueue()
            }
        }
    }

    fun resumeDownload(taskId: String): Job = scope.launch {
        resumeDownloadSync(taskId)
    }

    suspend fun cancelDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // 1. Invalidate execution identity
            activeRunIds.remove(taskId)

            // 2. Stop running process and clean up temporary files
            cancelDownloadInternal(taskId, cleanupFiles = true)

            // 3. Persist CANCELLED state
            val task = repository.getTaskByIdSync(taskId)
            if (task != null && task.status != DownloadStatus.COMPLETED) {
                repository.updateTask(
                    task.copy(
                        status = DownloadStatus.CANCELLED,
                        stage = DownloadStage.QUEUED,
                        runId = 0L,
                        downloadSpeed = "",
                        eta = ""
                    )
                )
                DownloadForegroundService.updateOrDismissIfIdle(
                    context, taskId, task.title, DownloadStatus.CANCELLED, task.progress.toInt(), ""
                )
            }

            processQueue()
        }
    }

    fun cancelDownload(taskId: String): Job = scope.launch {
        cancelDownloadSync(taskId)
    }

    suspend fun retryDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // 1. Invalidate old runId
            activeRunIds.remove(taskId)

            // 2. Cancel any running job and wait for exit
            val job = activeJobs.remove(taskId)
            job?.cancel()
            job?.join()
            try { downloadEngine.cancel(taskId) } catch (_: Throwable) {}

            val task = repository.getTaskByIdSync(taskId) ?: return@withLock

            // 3. Allocate fresh runId for the retry
            val newRunId = generateRunId()

            // 4. Reset task state to QUEUED
            val updatedTask = task.copy(
                status = DownloadStatus.QUEUED,
                stage = DownloadStage.QUEUED,
                runId = newRunId,
                progress = 0f,
                errorMessage = null,
                downloadSpeed = "",
                eta = "",
                retryCount = task.retryCount + 1,
                queueOrder = System.currentTimeMillis()
            )

            repository.updateTask(updatedTask)
            DownloadForegroundService.updateOrDismissIfIdle(
                context, taskId, task.title, DownloadStatus.QUEUED, 0, ""
            )
            processQueue()
        }
    }

    fun retryDownload(taskId: String): Job = scope.launch {
        retryDownloadSync(taskId)
    }

    /**
     * Delete sequence strictly compliant with requirements:
     * 1. Invalidate runId
     * 2. Cancel process
     * 3. Await process exit
     * 4. Clean up files
     * 5. Delete DB record
     */
    suspend fun deleteDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // 1. Invalidate runId immediately
            activeRunIds.remove(taskId)

            // 2. Cancel process
            try {
                downloadEngine.cancel(taskId)
            } catch (_: Throwable) {}
            val job = activeJobs.remove(taskId)
            job?.cancel()

            // 3. Wait for process exit completely
            job?.join()

            speedSmoothers.remove(taskId)
            lastProgressUpdateTimes.remove(taskId)
            lastReportedProgress.remove(taskId)
            lastNotificationTimes.remove(taskId)
            _activeDownloadCount.value = activeJobs.size

            // 4. Cleanup files
            CleanupManager.cleanupTaskFiles(context, taskId)
            DownloadForegroundService.updateOrDismissIfIdle(
                context, taskId, "", DownloadStatus.CANCELLED, 0, ""
            )

            // 5. Delete DB record
            repository.deleteTask(taskId)

            taskMutexes.remove(taskId)
            processQueue()
        }
    }

    fun deleteDownload(taskId: String): Job = scope.launch {
        deleteDownloadSync(taskId)
    }

    fun reorderTask(taskId: String, newOrder: Long) {
        scope.launch {
            repository.updateQueueOrder(taskId, newOrder)
            processQueue()
        }
    }

    fun moveTaskUp(taskId: String) {
        scope.launch {
            val queued = repository.getQueuedTasks()
            val index = queued.indexOfFirst { it.id == taskId }
            if (index > 0) {
                val currentTask = queued[index]
                val prevTask = queued[index - 1]
                val newOrder = prevTask.queueOrder - 1
                repository.updateQueueOrder(currentTask.id, newOrder)
            }
        }
    }

    fun moveTaskDown(taskId: String) {
        scope.launch {
            val queued = repository.getQueuedTasks()
            val index = queued.indexOfFirst { it.id == taskId }
            if (index >= 0 && index < queued.size - 1) {
                val currentTask = queued[index]
                val nextTask = queued[index + 1]
                val newOrder = nextTask.queueOrder + 1
                repository.updateQueueOrder(currentTask.id, newOrder)
            }
        }
    }

    fun bulkCancel(taskIds: List<String>) {
        taskIds.forEach { cancelDownload(it) }
    }

    fun bulkRetry(taskIds: List<String>) {
        taskIds.forEach { retryDownload(it) }
    }

    fun bulkDelete(taskIds: List<String>) {
        scope.launch {
            for (id in taskIds) {
                deleteDownloadSync(id)
            }
        }
    }

    fun clearHistory(deletePhysicalFiles: Boolean) {
        scope.launch {
            if (deletePhysicalFiles) {
                val completedTasks = repository.getAllCompletedTasksSync()
                for (task in completedTasks) {
                    task.filePath?.let { path ->
                        try {
                            File(path).delete()
                        } catch (_: Exception) {}
                    }
                    task.contentUri?.let { uriStr ->
                        try {
                            context.contentResolver.delete(Uri.parse(uriStr), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
            repository.clearFinishedTasks()
        }
    }

    /**
     * Dispatches queued downloads according to concurrency limit.
     */
    suspend fun processQueue() {
        queueMutex.withLock {
            val maxConcurrency = appSettings.concurrentDownloads.value.coerceIn(1, 3)
            val currentActiveCount = activeJobs.size
            val availableSlots = maxConcurrency - currentActiveCount

            _activeDownloadCount.value = currentActiveCount

            if (availableSlots <= 0) {
                return
            }

            if (!networkMonitor.isOnline()) {
                return
            }

            val queuedTasks = repository.getQueuedTasks()
            val tasksToStart = queuedTasks.take(availableSlots)

            for (task in tasksToStart) {
                if (!activeJobs.containsKey(task.id)) {
                    val job = scope.launch {
                        executeDownloadTask(task.id)
                    }
                    activeJobs[task.id] = job
                    _activeDownloadCount.value = activeJobs.size
                }
            }
        }
    }

    private suspend fun executeDownloadTask(taskId: String) {
        val task = repository.getTaskByIdSync(taskId) ?: run {
            activeJobs.remove(taskId)
            activeRunIds.remove(taskId)
            _activeDownloadCount.value = activeJobs.size
            return
        }

        // 1. Point 1 & 2: Allocate unique runId for this actual execution
        val executionRunId = generateRunId()
        activeRunIds[taskId] = executionRunId

        // Storage Check: Calculated storage based on payload, streams, ffmpeg intermediate, and safety margin
        val storageCheck = StorageSpaceChecker.validateDownloadSpace(context, task)
        if (!storageCheck.hasEnoughSpace) {
            val failedTask = task.copy(
                status = DownloadStatus.FAILED,
                stage = DownloadStage.QUEUED,
                runId = executionRunId,
                errorMessage = storageCheck.errorMessage ?: "Insufficient storage space available."
            )
            repository.updateTask(failedTask)
            activeJobs.remove(taskId)
            activeRunIds.remove(taskId)
            _activeDownloadCount.value = activeJobs.size
            processQueue()
            return
        }

        // Update stage to PREPARING
        repository.updateTask(
            task.copy(
                status = DownloadStatus.PREPARING,
                stage = DownloadStage.PREPARING,
                runId = executionRunId
            )
        )
        DownloadForegroundService.startOrUpdate(context, taskId, task.title, 0, DownloadStatus.PREPARING, "")

        // Update stage to DOWNLOADING
        repository.updateTask(
            task.copy(
                status = DownloadStatus.DOWNLOADING,
                stage = DownloadStage.DOWNLOADING,
                runId = executionRunId
            )
        )
        DownloadForegroundService.startOrUpdate(context, taskId, task.title, task.progress.toInt(), DownloadStatus.DOWNLOADING, "")

        val cutMode = if (task.cutMode.equals("precise", ignoreCase = true)) CutMode.PRECISE_CUT else CutMode.FAST_CUT
        val request = DownloadRequest(
            id = task.id,
            runId = executionRunId,
            url = task.url,
            formatSelector = task.formatId,
            startTime = task.startTime,
            endTime = task.endTime,
            cutMode = cutMode,
            title = task.title,
            thumbnailUrl = task.thumbnailUrl,
            formatDescription = task.formatDescription,
            isAudioOnly = task.isAudioOnly,
            isVideoOnly = task.isVideoOnly,
            downloadSubtitles = task.downloadSubtitles,
            subtitleLanguage = task.subtitleLanguage
        )

        val smoother = speedSmoothers.getOrPut(taskId) { SpeedSmoother() }

        val result = downloadEngine.download(request) { progress ->
            handleProgressUpdate(
                taskId = taskId,
                runId = if (progress.runId > 0L) progress.runId else executionRunId,
                stage = progress.stage,
                title = task.title,
                progress = progress.progressPercentage,
                speed = progress.speed,
                eta = progress.eta,
                downloadedBytes = progress.downloadedBytes,
                totalBytes = progress.totalBytes,
                smoother = smoother
            )
        }

        activeJobs.remove(taskId)
        speedSmoothers.remove(taskId)
        lastProgressUpdateTimes.remove(taskId)
        lastReportedProgress.remove(taskId)
        lastNotificationTimes.remove(taskId)
        _activeDownloadCount.value = activeJobs.size

        // Point 4: If runId was invalidated or replaced, ignore the completion
        if (activeRunIds[taskId] != executionRunId) {
            return
        }

        result.fold(
            onSuccess = { finalFile ->
                if (activeRunIds[taskId] != executionRunId) {
                    return@fold
                }

                // Advance to PUBLISHING stage
                val currentBeforePublish = repository.getTaskByIdSync(taskId)
                if (currentBeforePublish != null) {
                    repository.updateTask(
                        currentBeforePublish.copy(
                            stage = DownloadStage.PUBLISHING,
                            runId = executionRunId
                        )
                    )
                }

                val (uri, savedPath) = MediaStoreHelper.saveToPublicDownloads(context, finalFile, task.title)
                if (uri == null || savedPath.isNullOrBlank()) {
                    val storageFailure = DownloadError.StorageError(
                        msg = "Failed to save downloaded file to device storage.",
                        detail = "MediaStore insert or file copy failed."
                    )
                    handleDownloadFailure(taskId, executionRunId, task, storageFailure)
                    return@fold
                }

                if (finalFile.exists() && savedPath != finalFile.absolutePath) {
                    finalFile.delete()
                }

                if (activeRunIds[taskId] != executionRunId) {
                    return@fold
                }

                val current = repository.getTaskByIdSync(taskId)
                val f = File(savedPath)
                val finalFileSize = if (f.exists()) CleanupManager.formatFileSize(f.length()) else ""

                val completedTask = current?.copy(
                    status = DownloadStatus.COMPLETED,
                    stage = DownloadStage.COMPLETED,
                    runId = executionRunId,
                    progress = 100f,
                    contentUri = uri.toString(),
                    filePath = savedPath,
                    downloadSpeed = "",
                    eta = "",
                    downloadedSize = if (current.downloadedSize.isNotBlank()) current.downloadedSize else finalFileSize,
                    totalSize = if (current.totalSize.isNotBlank()) current.totalSize else finalFileSize,
                    completedAt = System.currentTimeMillis()
                )
                if (completedTask != null) {
                    repository.updateTask(completedTask)
                }
                activeRunIds.remove(taskId)
                DownloadForegroundService.onTaskCompleted(context, taskId, task.title, uri.toString())
            },
            onFailure = { error ->
                handleDownloadFailure(taskId, executionRunId, task, error)
            }
        )

        processQueue()
    }

    private suspend fun handleDownloadFailure(
        taskId: String,
        runId: Long,
        originalTask: DownloadTaskEntity,
        error: Throwable
    ) {
        if (activeRunIds[taskId] != runId) {
            // Run was superseded, cancelled, or paused
            return
        }
        activeRunIds.remove(taskId)

        val isCancelled = error is DownloadError.Cancelled ||
                error.message?.contains("destroy", ignoreCase = true) == true ||
                error.message?.contains("interrupted", ignoreCase = true) == true ||
                error.message?.contains("cancel", ignoreCase = true) == true

        val currentTask = repository.getTaskByIdSync(taskId) ?: originalTask
        if (DownloadStateMachine.isTerminalOrPaused(currentTask.status)) {
            return
        }

        val finalStatus = if (isCancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED
        val errorTask = currentTask.copy(
            status = finalStatus,
            downloadSpeed = "",
            eta = "",
            errorMessage = error.localizedMessage ?: "Download failed"
        )
        repository.updateTask(errorTask)

        if (finalStatus == DownloadStatus.FAILED) {
            val isRetryable = RetryPolicy.isRetryable(error)
            val canAutoRetry = appSettings.autoRetry.value && currentTask.retryCount < RetryPolicy.MAX_RETRIES && isRetryable

            if (canAutoRetry) {
                val delayMs = RetryPolicy.getBackoffDelayMs(currentTask.retryCount)
                scope.launch {
                    delay(delayMs)
                    retryDownload(taskId)
                }
            } else {
                DownloadForegroundService.onTaskFailed(
                    context, taskId, originalTask.title, error.localizedMessage ?: "Download error"
                )
            }
        } else {
            DownloadForegroundService.updateOrDismissIfIdle(
                context, taskId, originalTask.title, finalStatus, 0, ""
            )
        }
    }

    private fun handleProgressUpdate(
        taskId: String,
        runId: Long,
        stage: DownloadStage,
        title: String,
        progress: Float,
        speed: String,
        eta: String,
        downloadedBytes: Long,
        totalBytes: Long,
        smoother: SpeedSmoother
    ) {
        // Point 3 & 4: In-memory identity check. Drops callbacks from stale runs immediately
        val expectedRunId = activeRunIds[taskId]
        if (expectedRunId == null || expectedRunId != runId) {
            return
        }

        if (!activeJobs.containsKey(taskId)) {
            return
        }

        val now = System.currentTimeMillis()
        val lastTime = lastProgressUpdateTimes[taskId] ?: 0L
        val lastProg = lastReportedProgress[taskId] ?: 0f
        val progressDelta = Math.abs(progress - lastProg)

        val isSignificant = progressDelta >= 0.5f || progress >= 100f || (now - lastTime >= 400L)
        if (!isSignificant) {
            return
        }

        lastProgressUpdateTimes[taskId] = now
        lastReportedProgress[taskId] = progress

        val speedText = speed
        val downloadedText = if (downloadedBytes > 0) CleanupManager.formatFileSize(downloadedBytes) else ""
        val totalText = if (totalBytes > 0) CleanupManager.formatFileSize(totalBytes) else ""

        val effectiveStatus = when (stage) {
            DownloadStage.MERGING, DownloadStage.CUTTING -> DownloadStatus.PROCESSING_FFMPEG
            else -> DownloadStatus.DOWNLOADING
        }

        scope.launch {
            // Re-verify execution identity inside coroutine dispatch
            if (activeRunIds[taskId] != runId) {
                return@launch
            }

            val updatedRows = repository.updateProgress(
                id = taskId,
                runId = runId,
                status = effectiveStatus,
                stage = stage,
                progress = progress,
                downloadSpeed = speedText,
                eta = eta,
                downloadedSize = downloadedText,
                totalSize = totalText
            )

            // If updatedRows is 0, Room protected the record (status was PAUSED, CANCELLED, COMPLETED, FAILED, or runId mismatch)
            if (updatedRows == 0) {
                return@launch
            }

            val lastNotif = lastNotificationTimes[taskId] ?: 0L
            if (now - lastNotif >= 1000L || progress >= 100f) {
                lastNotificationTimes[taskId] = now
                DownloadForegroundService.startOrUpdate(
                    context = context,
                    taskId = taskId,
                    title = title,
                    progress = progress.toInt(),
                    status = effectiveStatus,
                    speed = speedText
                )
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadQueueManager? = null

        fun getInstance(context: Context): DownloadQueueManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DownloadQueueManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
