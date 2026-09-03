package com.example.ui.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DownloadVideosApplication
import com.example.data.local.DownloadTaskEntity
import com.example.domain.model.CutMode
import com.example.domain.model.FormatOption
import com.example.domain.model.TimeRange
import com.example.domain.model.VideoMetadata
import com.example.domain.validator.TimeValidationResult
import com.example.domain.validator.TimeValidator
import com.example.downloader.DownloadManager
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.engine.YtDlpMediaEngine
import com.example.ytdlp.FormatParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FormatCategory {
    VIDEO,
    AUDIO,
    ADVANCED
}

data class DownloaderUiState(
    val urlInput: String = "",
    val isAnalyzing: Boolean = false,
    val analysisError: String? = null,
    val videoMetadata: VideoMetadata? = null,
    val selectedFormat: FormatOption? = null,
    val activeCategory: FormatCategory = FormatCategory.VIDEO,
    val videoFormats: List<FormatOption> = emptyList(),
    val audioFormats: List<FormatOption> = emptyList(),
    val allFormats: List<FormatOption> = emptyList(),
    val customFormatId: String = "",
    val isCustomFormatEnabled: Boolean = false,
    val isTimeTrimEnabled: Boolean = false,
    val startTime: String = "00:00:00",
    val endTime: String = "",
    val cutMode: CutMode = CutMode.FAST_CUT,
    val timeValidationError: String? = null,
    val isEngineUpdating: Boolean = false,
    val engineMessage: String? = null,
    val duplicateTaskFound: DownloadTaskEntity? = null
)

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as? DownloadVideosApplication
    private val mediaEngine: YtDlpMediaEngine = app?.container?.ytDlpMediaEngine ?: YtDlpDownloadEngine.getInstance(application)
    private val downloadManager = DownloadManager.getInstance(application)

    private val _uiState = MutableStateFlow(DownloaderUiState())
    val uiState: StateFlow<DownloaderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            mediaEngine.init(getApplication())
        }
    }

    fun onUrlChanged(url: String) {
        _uiState.update { it.copy(urlInput = url, analysisError = null) }
    }

    fun clearUrl() {
        _uiState.update {
            it.copy(
                urlInput = "",
                videoMetadata = null,
                analysisError = null,
                selectedFormat = null,
                duplicateTaskFound = null
            )
        }
    }

    fun analyzeUrl() {
        val currentUrl = _uiState.value.urlInput.trim()
        if (currentUrl.isBlank()) {
            _uiState.update { it.copy(analysisError = "Please enter a valid video URL") }
            return
        }

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                analysisError = null,
                videoMetadata = null,
                selectedFormat = null,
                duplicateTaskFound = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = mediaEngine.fetchVideoInfo(currentUrl)
            result.fold(
                onSuccess = { metadata ->
                    val (vFormats, aFormats, all) = FormatParser.getCategorizedOptions(metadata.formats)
                    val defaultFormat = vFormats.firstOrNull() ?: all.firstOrNull()
                    val defaultEndTime = if (metadata.durationSeconds > 0) {
                        TimeValidator.formatSecondsToTimestamp(metadata.durationSeconds)
                    } else "00:01:00"

                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            videoMetadata = metadata,
                            selectedFormat = defaultFormat,
                            videoFormats = vFormats,
                            audioFormats = aFormats,
                            allFormats = all,
                            endTime = defaultEndTime,
                            analysisError = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            analysisError = err.message ?: "Video is invalid or unavailable."
                        )
                    }
                }
            )
        }
    }

    fun selectCategory(category: FormatCategory) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    fun selectFormat(format: FormatOption) {
        _uiState.update { it.copy(selectedFormat = format, isCustomFormatEnabled = false) }
    }

    fun setCustomFormatId(id: String) {
        _uiState.update { it.copy(customFormatId = id, isCustomFormatEnabled = id.isNotBlank()) }
    }

    fun toggleTimeTrim(enabled: Boolean) {
        _uiState.update { it.copy(isTimeTrimEnabled = enabled, timeValidationError = null) }
    }

    fun onStartTimeChanged(time: String) {
        _uiState.update { it.copy(startTime = time) }
        validateTimeInput()
    }

    fun onEndTimeChanged(time: String) {
        _uiState.update { it.copy(endTime = time) }
        validateTimeInput()
    }

    fun setCutMode(mode: CutMode) {
        _uiState.update { it.copy(cutMode = mode) }
    }

    private fun validateTimeInput(): Boolean {
        val state = _uiState.value
        if (!state.isTimeTrimEnabled) {
            _uiState.update { it.copy(timeValidationError = null) }
            return true
        }

        val duration = state.videoMetadata?.durationSeconds
        val validation = TimeValidator.validate(state.startTime, state.endTime, duration)
        return when (validation) {
            is TimeValidationResult.Success -> {
                _uiState.update { it.copy(timeValidationError = null) }
                true
            }
            is TimeValidationResult.Error -> {
                _uiState.update { it.copy(timeValidationError = validation.message) }
                false
            }
        }
    }

    fun checkForDuplicateAndDownload(onStartDownload: () -> Unit) {
        val state = _uiState.value
        val metadata = state.videoMetadata ?: return

        val formatIdToUse = if (state.isCustomFormatEnabled && state.customFormatId.isNotBlank()) {
            state.customFormatId.trim()
        } else {
            state.selectedFormat?.formatId ?: "best"
        }

        val sTime = if (state.isTimeTrimEnabled) state.startTime.trim() else null
        val eTime = if (state.isTimeTrimEnabled) state.endTime.trim() else null

        viewModelScope.launch(Dispatchers.IO) {
            val duplicate = downloadManager.checkDuplicate(
                url = metadata.webpageUrl.ifBlank { state.urlInput },
                formatId = formatIdToUse,
                startTime = sTime,
                endTime = eTime
            )

            if (duplicate != null) {
                _uiState.update { it.copy(duplicateTaskFound = duplicate) }
            } else {
                onStartDownload()
            }
        }
    }

    fun dismissDuplicateDialog() {
        _uiState.update { it.copy(duplicateTaskFound = null) }
    }

    fun forceStartDownload(): String? {
        val state = _uiState.value
        val metadata = state.videoMetadata ?: return null

        if (state.isTimeTrimEnabled && !validateTimeInput()) {
            return null
        }

        val formatIdToUse = if (state.isCustomFormatEnabled && state.customFormatId.isNotBlank()) {
            state.customFormatId.trim()
        } else {
            state.selectedFormat?.formatId ?: "best"
        }

        val isAudioOnly = state.selectedFormat?.isAudioOnly == true && !state.isCustomFormatEnabled

        val timeRange = if (state.isTimeTrimEnabled) {
            TimeRange(
                startTime = state.startTime.trim(),
                endTime = state.endTime.trim(),
                cutMode = state.cutMode
            )
        } else null

        val formatDesc = if (state.isCustomFormatEnabled) {
            "Custom ID: $formatIdToUse"
        } else {
            state.selectedFormat?.displayTitle ?: "Best Quality"
        }

        _uiState.update { it.copy(duplicateTaskFound = null) }

        return downloadManager.startDownload(
            url = metadata.webpageUrl.ifBlank { state.urlInput },
            title = metadata.title,
            thumbnailUrl = metadata.thumbnailUrl,
            formatId = formatIdToUse,
            formatDescription = formatDesc,
            isAudioOnly = isAudioOnly,
            timeRange = timeRange
        )
    }

    fun updateEngine() {
        _uiState.update { it.copy(isEngineUpdating = true, engineMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = mediaEngine.updateEngine(getApplication())
            result.fold(
                onSuccess = { msg ->
                    _uiState.update { it.copy(isEngineUpdating = false, engineMessage = "Engine status: $msg") }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isEngineUpdating = false, engineMessage = "Update error: ${err.message}") }
                }
            )
        }
    }

    fun dismissEngineMessage() {
        _uiState.update { it.copy(engineMessage = null) }
    }
}
