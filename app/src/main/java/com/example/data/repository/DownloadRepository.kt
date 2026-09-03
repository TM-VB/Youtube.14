package com.example.data.repository

import com.example.data.local.DownloadTaskDao
import com.example.data.local.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val dao: DownloadTaskDao) {

    val allTasks: Flow<List<DownloadTaskEntity>> = dao.getAllTasks()

    fun getTaskById(id: String): Flow<DownloadTaskEntity?> = dao.getTaskById(id)

    suspend fun getTaskByIdSync(id: String): DownloadTaskEntity? = dao.getTaskByIdSync(id)

    suspend fun getQueuedTasks(): List<DownloadTaskEntity> = dao.getQueuedTasks()

    suspend fun getActiveTasksSync(): List<DownloadTaskEntity> = dao.getActiveTasksSync()

    suspend fun markActiveTasksAsInterrupted(): Int = dao.markActiveTasksAsInterrupted()

    suspend fun insertTask(task: DownloadTaskEntity) = dao.insertTask(task)

    suspend fun updateTask(task: DownloadTaskEntity) = dao.updateTask(task)

    suspend fun updateProgress(
        id: String,
        runId: Long,
        status: com.example.domain.model.DownloadStatus,
        stage: com.example.domain.model.DownloadStage,
        progress: Float,
        downloadSpeed: String,
        eta: String,
        downloadedSize: String = "",
        totalSize: String = ""
    ): Int = dao.updateProgress(id, runId, status, stage, progress, downloadSpeed, eta, downloadedSize, totalSize)

    suspend fun updateProgress(
        id: String,
        status: com.example.domain.model.DownloadStatus,
        progress: Float,
        downloadSpeed: String,
        eta: String,
        downloadedSize: String = "",
        totalSize: String = ""
    ): Int = dao.updateProgress(id, status, progress, downloadSpeed, eta, downloadedSize, totalSize)

    suspend fun findExistingTask(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity? =
        dao.findExistingTask(url, formatId, startTime, endTime)

    suspend fun updateQueueOrder(id: String, order: Long) = dao.updateQueueOrder(id, order)

    suspend fun deleteTasksByIds(ids: List<String>) = dao.deleteTasksByIds(ids)

    suspend fun getAllCompletedTasksSync(): List<DownloadTaskEntity> = dao.getAllCompletedTasksSync()

    suspend fun deleteTask(id: String) = dao.deleteTaskById(id)

    suspend fun clearFinishedTasks() = dao.clearFinishedTasks()
}
