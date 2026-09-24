package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.Recording
import com.example.data.database.RecordingDatabase
import com.example.data.gemini.GeminiClient
import com.example.data.repository.RecordingRepository
import com.example.services.CallStateTracker
import com.example.utils.AudioPlayerManager
import com.example.utils.AudioRecorderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class CallRecorderViewModel(
    application: Application,
    private val repository: RecordingRepository,
    private val recorder: AudioRecorderManager,
    private val player: AudioPlayerManager
) : AndroidViewModel(application) {
    private val TAG = "CallRecorderVM"

    // Compat constructor: resolves from SajilApplication container.
    // New code should use Factory with explicit deps (testable).
    constructor(application: Application) : this(
        application,
        (application as? com.example.SajilApplication)?.container?.repository
            ?: RecordingRepository(
                application,
                RecordingDatabase.getDatabase(application).recordingDao()
            ),
        (application as? com.example.SajilApplication)?.container?.recorderManager
            ?: AudioRecorderManager(application),
        (application as? com.example.SajilApplication)?.container?.playerManager
            ?: AudioPlayerManager(application)
    )

    // Kept for compat (UI calls via playRecording/seek wrappers).
    // Prefer injected [recorder]/[player]; these delegate for now.
    val recorderManager: AudioRecorderManager get() = recorder
    val playerManager: AudioPlayerManager get() = player

    class Factory(
        private val app: Application,
        private val repository: RecordingRepository? = null,
        private val recorder: AudioRecorderManager? = null,
        private val player: AudioPlayerManager? = null
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val container = (app as? com.example.SajilApplication)?.container
            return CallRecorderViewModel(
                app,
                repository ?: container?.repository
                    ?: RecordingRepository(
                        app,
                        RecordingDatabase.getDatabase(app).recordingDao()
                    ),
                recorder ?: container?.recorderManager ?: AudioRecorderManager(app),
                player ?: container?.playerManager ?: AudioPlayerManager(app)
            ) as T
        }
    }

    private val recordingMutex = Mutex()
    private val aiJobs = mutableMapOf<Long, Job>()

    // Filter states
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedSourceFilter = MutableStateFlow("ALL") // ALL, CELLULAR, WHATSAPP, MESSENGER, MIC
    val selectedSourceFilter = _selectedSourceFilter.asStateFlow()

    // Recording list flow combined with filters.
    // Search input is debounced + distinct so each keystroke does not re-filter
    // the full table; source filter is a cheap equality check.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val recordings: StateFlow<List<Recording>> = combine(
        repository.allRecordings,
        _searchQuery.debounce(300).distinctUntilChanged(),
        _selectedSourceFilter
    ) { rawList, query, filter ->
        var list = rawList
        if (query.isNotBlank()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                (it.transcript ?: "").contains(query, ignoreCase = true) ||
                (it.notes ?: "").contains(query, ignoreCase = true)
            }
        }
        if (filter != "ALL") {
            list = list.filter { it.source.equals(filter, ignoreCase = true) }
        }
        list
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Recording state
    private val _isRecordingActive = MutableStateFlow(false)
    val isRecordingActive = _isRecordingActive.asStateFlow()

    private val _activeRecordDurationSec = MutableStateFlow(0)
    val activeRecordDurationSec = _activeRecordDurationSec.asStateFlow()

    private val _amplitudeList = MutableStateFlow<List<Float>>(emptyList())
    val amplitudeList = _amplitudeList.asStateFlow()

    // Simulation states
    private val _activeSimulatedCall = MutableStateFlow<SimulatedCall?>(null)
    val activeSimulatedCall = _activeSimulatedCall.asStateFlow()

    // AI Operation States
    private val _aiOperationState = MutableStateFlow<AiOpState>(AiOpState.Idle)
    val aiOperationState = _aiOperationState.asStateFlow()

    // Active Player details (StateFlow from PlayerManager)
    val playbackState = player.playbackState

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    // Selected recording for detail view
    private val _selectedRecording = MutableStateFlow<Recording?>(null)
    val selectedRecording = _selectedRecording.asStateFlow()

    // Recording/Sim timers
    private var recordingTimerJob: Job? = null
    private var amplitudeJob: Job? = null

    // Foreground tap-to-record fallback: started from MainActivity when the
    // user taps the "call in progress" notification (background auto-start
    // blocked by the OS). stopMicRecording() branches on [isTapRecording]
    // to file it as a cellular call instead of a mic memo.
    private var isTapRecording = false
    private var tapTitle = "مكالمة"
    private var tapDirection = "INBOUND"

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Seed sample data for high-polish first load experience
            repository.prepopulateIfEmpty()
            // Drop phantom rows (missing/0-byte files from restores or
            // manual deletion) so history never shows unplayable corpses.
            try {
                repository.cleanupMissingFiles()
            } catch (e: Exception) {
                Log.e(TAG, "Startup sweep failed", e)
            }
        }
    }

    // Set filters
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSourceFilter(filter: String) {
        _selectedSourceFilter.value = filter
    }

    fun selectRecording(recording: Recording?) {
        val current = _selectedRecording.value
        _selectedRecording.value = recording
        // Avoid interrupting playback on rotation / re-select of same item.
        if (current?.id != recording?.id) {
            player.stopAudio()
        }
    }

    // Audio Playback Actions
    fun playRecording(recording: Recording) {
        player.playAudio(recording.filePath, _playbackSpeed.value)
    }

    fun pausePlayback() {
        player.pauseAudio()
    }

    fun resumePlayback() {
        player.resumeAudio()
    }

    fun stopPlayback() {
        player.stopAudio()
    }

    fun seekPlaybackTo(positionMs: Int) {
        player.seekTo(positionMs)
    }

    fun updatePlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        player.setPlaybackSpeed(speed)
    }

    // Delete recording
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_selectedRecording.value?.id == recording.id) {
                _selectedRecording.value = null
            }
            // Stop playing if deleting current playing item
            if (player.getCurrentPlayingPath() == recording.filePath) {
                player.stopAudio()
            }
            repository.delete(recording)
        }
    }

    // Update notes
    fun updateNotes(recordingId: Long, newNotes: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rec = repository.getRecordingById(recordingId)
            if (rec != null) {
                val updated = rec.copy(notes = newNotes)
                repository.update(updated)
                if (_selectedRecording.value?.id == recordingId) {
                    _selectedRecording.value = updated
                }
            }
        }
    }

    // Recorder Actions — serialized by Mutex so double-tap cannot start
    // two MediaRecorders. Blocking recorder calls run on IO; UI state
    // updates are posted back on Main.
    fun startMicRecording() {
        viewModelScope.launch {
            recordingMutex.withLock {
                if (_isRecordingActive.value) return@withLock
                val file = withContext(Dispatchers.IO) {
                    recorder.startRecording("sajil_mic")
                }
                if (file != null && file.exists() && file.length() >= 0L) {
                    _isRecordingActive.value = true
                    _activeRecordDurationSec.value = 0
                    _amplitudeList.value = emptyList()
                    startRecordingTimers()
                } else {
                    Log.e(TAG, "Mic recording failed to start (null file)")
                }
            }
        }
    }

    fun stopMicRecording() {
        viewModelScope.launch {
            recordingMutex.withLock {
                if (!_isRecordingActive.value) return@withLock
                stopRecordingTimers()
                val result = withContext(Dispatchers.IO) { recorder.stopRecording() }
                _isRecordingActive.value = false

                val file = result.file
                if (file != null && file.exists() && file.length() > 0L && result.durationSec > 0) {
                    val wasTap = isTapRecording
                    isTapRecording = false
                    val newRecording = Recording(
                        title = if (wasTap) tapTitle else "تسجيل صوتي عابر",
                        source = if (wasTap) "CELLULAR" else "MIC",
                        direction = if (wasTap) tapDirection else "MEMO",
                        durationSec = result.durationSec,
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        notes = if (wasTap) "مكالمة مسجلة عبر زر بدء التسجيل."
                        else "تسجيل شخصي من الميكروفون."
                    )
                    val id = withContext(Dispatchers.IO) { repository.insert(newRecording) }
                    val insertedRec = withContext(Dispatchers.IO) { repository.getRecordingById(id) }
                    if (insertedRec != null) {
                        _selectedRecording.value = insertedRec
                    }
                } else {
                    Log.w(TAG, "Mic recording produced no valid file, discarding")
                }
            }
        }
    }

    /**
     * Foreground fallback for blocked background auto-start: the user taps
     * the "call in progress" notification, the app comes to the foreground
     * (where mic access is always allowed), and this starts recording with
     * call metadata. Stopped via [stopMicRecording] (same stop button).
     */
    fun startCallTapRecording() {
        viewModelScope.launch {
            recordingMutex.withLock {
                if (_isRecordingActive.value) return@withLock
                val pendingFresh =
                    System.currentTimeMillis() - CallStateTracker.pendingAtMillis < 60_000L
                val number = if (pendingFresh) CallStateTracker.pendingNumber else null
                val file = withContext(Dispatchers.IO) {
                    recorder.startRecording("call_tap")
                }
                if (file != null && file.exists()) {
                    tapTitle = number?.takeIf { it.isNotBlank() } ?: "مكالمة"
                    tapDirection =
                        if (pendingFresh) CallStateTracker.pendingDirection else "INBOUND"
                    isTapRecording = true
                    _isRecordingActive.value = true
                    _activeRecordDurationSec.value = 0
                    _amplitudeList.value = emptyList()
                    startRecordingTimers()
                } else {
                    Log.e(TAG, "Tap-to-record failed to start (null file)")
                }
            }
        }
    }

    // SIMULATED CALL ACTIONS
    fun initiateQuickTestCall() {
        initiateSimulatedCall(
            callerName = "مكالمة تجريبية تلقائية",
            platform = "CELLULAR",
            isInbound = true
        )
    }

    fun initiateSimulatedCall(callerName: String, platform: String, isInbound: Boolean) {
        viewModelScope.launch {
            recordingMutex.withLock {
                if (_activeSimulatedCall.value != null || _isRecordingActive.value) return@withLock
                val prefix = "sim_${platform.lowercase()}_${if (isInbound) "in" else "out"}"
                val file = withContext(Dispatchers.IO) { recorder.startRecording(prefix) }
                if (file != null) {
                    _activeSimulatedCall.value = SimulatedCall(
                        callerName = callerName,
                        platform = platform,
                        isInbound = isInbound,
                        filePath = file.absolutePath,
                        startTime = System.currentTimeMillis()
                    )
                    _activeRecordDurationSec.value = 0
                    _amplitudeList.value = emptyList()
                    startRecordingTimers()
                }
            }
        }
    }

    fun endSimulatedCall() {
        viewModelScope.launch {
            recordingMutex.withLock {
                val activeCall = _activeSimulatedCall.value ?: return@withLock
                stopRecordingTimers()
                val result = withContext(Dispatchers.IO) { recorder.stopRecording() }
                _activeSimulatedCall.value = null

                val file = result.file
                if (file != null && file.exists() && file.length() > 0L && result.durationSec > 0) {
                    val newRecording = Recording(
                        title = activeCall.callerName,
                        source = activeCall.platform,
                        direction = if (activeCall.isInbound) "INBOUND" else "OUTBOUND",
                        durationSec = result.durationSec,
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        notes = "مكالمة مسجلة من تطبيق ${activeCall.platform}."
                    )
                    val id = withContext(Dispatchers.IO) { repository.insert(newRecording) }
                    val insertedRec = withContext(Dispatchers.IO) { repository.getRecordingById(id) }
                    if (insertedRec != null) {
                        _selectedRecording.value = insertedRec
                    }
                } else {
                    Log.w(TAG, "Simulated call produced no valid file, discarding")
                }
            }
        }
    }

    private fun startRecordingTimers() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1000)
                _activeRecordDurationSec.value += 1
            }
        }

        amplitudeJob?.cancel()
        // Amplitude polls MediaRecorder (Binder) — keep it off Main to avoid
        // 10Hz UI jank. StateFlow is thread-safe; Compose collects on Main.
        amplitudeJob = viewModelScope.launch(Dispatchers.IO) {
            val window = ArrayDeque<Float>(51)
            while (isActive) {
                delay(200)
                val amp = try {
                    recorder.getAmplitude()
                } catch (_: Exception) { 0 }
                val normalized = (amp.toFloat() / 32767f).coerceIn(0f, 1f)
                if (window.size >= 50) window.removeFirst()
                window.addLast(normalized)
                _amplitudeList.value = window.toList()
            }
        }
    }

    private fun stopRecordingTimers() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        amplitudeJob?.cancel()
        amplitudeJob = null
    }

    // GEMINI AI TRANSCRIPTION & ANALYSIS ACTIONS — one job per recording id,
    // last-write-wins clobber avoided by cancelling the previous job.
    fun transcribeRecording(recording: Recording) {
        aiJobs[recording.id]?.cancel()
        _aiOperationState.value = AiOpState.Transcribing(recording.id)
        aiJobs[recording.id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val transcript = GeminiClient.generateTranscript(
                    callerName = recording.title,
                    source = recording.source,
                    durationSec = recording.durationSec,
                    userNotes = recording.notes,
                    audioFilePath = recording.filePath
                )

                if (GeminiClient.isMockText(transcript)) {
                    // Never persist the "add API key" warning as a real transcript,
                    // and never auto-analyze it.
                    _aiOperationState.value = AiOpState.Error("أضف مفتاح Gemini في الإعدادات أولاً")
                    return@launch
                }

                // Re-fetch latest row: user may have edited notes while we waited.
                val latest = repository.getRecordingById(recording.id) ?: recording
                val updated = latest.copy(
                    isTranscribed = true,
                    transcript = transcript
                )
                repository.update(updated)

                // Update active selection if needed
                if (_selectedRecording.value?.id == recording.id) {
                    _selectedRecording.value = updated
                }

                _aiOperationState.value = AiOpState.Success(recording.id, "تم نسخ الصوت إلى نص بنجاح!")

                // Auto transition to analyze
                analyzeRecording(updated)
            } catch (e: Exception) {
                Log.e(TAG, "Failed transcription", e)
                _aiOperationState.value = AiOpState.Error("خطأ أثناء تحويل الصوت")
            }
        }
    }

    fun analyzeRecording(recording: Recording) {
        // Resolve fresh transcript: caller may pass a stale copy.
        aiJobs[recording.id]?.cancel()
        aiJobs[recording.id] = viewModelScope.launch(Dispatchers.IO) {
            val latest = repository.getRecordingById(recording.id) ?: recording
            val transcript = latest.transcript ?: recording.transcript ?: return@launch
            if (GeminiClient.isMockText(transcript)) return@launch
            _aiOperationState.value = AiOpState.Analyzing(recording.id)
            try {
                val analysisResult = GeminiClient.analyzeTranscript(transcript)

                val fresh = repository.getRecordingById(recording.id) ?: latest
                val updated = fresh.copy(
                    summary = analysisResult.summary,
                    sentiment = analysisResult.sentiment,
                    importantPoints = analysisResult.importantPoints
                )
                repository.update(updated)

                if (_selectedRecording.value?.id == recording.id) {
                    _selectedRecording.value = updated
                }

                _aiOperationState.value = AiOpState.Success(recording.id, "تم تحليل المكالمة وتلخيصها بالذكاء الاصطناعي!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed analysis", e)
                _aiOperationState.value = AiOpState.Error("خطأ أثناء التحليل الذكي")
            }
        }
    }

    fun clearAiState() {
        _aiOperationState.value = AiOpState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        aiJobs.values.forEach { it.cancel() }
        aiJobs.clear()
        try {
            if (_isRecordingActive.value || _activeSimulatedCall.value != null) {
                recorder.stopRecording()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder in onCleared", e)
        }
        player.release()
        stopRecordingTimers()
    }

    data class SimulatedCall(
        val callerName: String,
        val platform: String,
        val isInbound: Boolean,
        val filePath: String,
        val startTime: Long
    )

    sealed interface AiOpState {
        object Idle : AiOpState
        data class Transcribing(val id: Long) : AiOpState
        data class Analyzing(val id: Long) : AiOpState
        data class Success(val id: Long, val msg: String) : AiOpState
        data class Error(val msg: String) : AiOpState
    }
}
