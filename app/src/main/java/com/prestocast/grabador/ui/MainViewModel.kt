package com.prestocast.grabador.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prestocast.grabador.audio.AudioPlayer
import com.prestocast.grabador.audio.AudioRecorder
import com.prestocast.grabador.audio.AudioRecordingService
import com.prestocast.grabador.audio.AudioTrimmer
import com.prestocast.grabador.audio.Mp3Transcoder
import com.prestocast.grabador.audio.TimeWarningManager
import com.prestocast.grabador.audio.TrimMode
import com.prestocast.grabador.data.AuphonicApiClient
import com.prestocast.grabador.data.AuphonicPreferences
import com.prestocast.grabador.data.AuphonicPreset
import com.prestocast.grabador.data.ProductionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RecordingStatus {
    object Idle : RecordingStatus()
    object Recording : RecordingStatus()
    object Paused : RecordingStatus()
}

enum class AudioSourceSelection {
    ORIGINAL,
    PROCESSED
}

sealed class AuphonicProcessState {
    object Idle : AuphonicProcessState()
    data class Working(val message: String, val progressPercent: Int? = null) : AuphonicProcessState()
    data class Success(
        val originalFile: File,
        val processedFile: File,
        val originalDurationMs: Long,
        val processedDurationMs: Long
    ) : AuphonicProcessState()
    data class Error(val message: String) : AuphonicProcessState()
}

data class UiState(
    val showSplashScreen: Boolean = true,
    val showAboutDialog: Boolean = false,
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordingTimeSeconds: Long = 0L,
    val currentAmplitude: Int = 0,
    val originalAudioFile: File? = null,
    val originalDurationMs: Long = 0L,
    val processedAudioFile: File? = null,
    val processedDurationMs: Long = 0L,
    val selectedAudioSource: AudioSourceSelection = AudioSourceSelection.ORIGINAL,
    val isOriginalPlaying: Boolean = false,
    val originalPlaybackPosMs: Int = 0,
    val isProcessedPlaying: Boolean = false,
    val processedPlaybackPosMs: Int = 0,
    val auphonicState: AuphonicProcessState = AuphonicProcessState.Idle,
    val showCredentialsDialog: Boolean = false,
    val showRecordingSettingsDialog: Boolean = false,
    val showTimeWarningDialog: Boolean = false,
    val showNoScriptDialog: Boolean = false,
    val showAuphonicResultScreen: Boolean = false,
    val timeWarningBannerText: String? = null,
    val rawAuphonicFile: File? = null,
    val selectedTrimMode: TrimMode = TrimMode.TRIM_END,
    val autoSaveOriginalToDownloads: Boolean = true,
    val timeWarningMode: Int = 0, // 0 = Sin aviso, 1 = 1 min, 2 = 10 min, 3 = Personalizado
    val timeWarningCustomMins: Int = 5,
    val isAutoScrollEnabled: Boolean = false,
    val autoScrollSpeed: Float = 5f,
    val recBitrateKbps: Int = 128,
    val recChannels: Int = 2,
    val recSampleRateHz: Int = 44100,
    val recNamePrefix: String = "Grabacion_",
    val processedSuffix: String = "_procesado",
    val recInputDeviceId: Int = -1,
    val recInputDeviceName: String = "Micrófonos del teléfono",
    val apiKey: String = "",
    val presetsList: List<AuphonicPreset> = emptyList(),
    val selectedPresetUuid: String = "",
    val isCustomPreset: Boolean = false,
    val customPresetInput: String = "",
    val trimAudioEnabled: Boolean = true,
    val isLoadingPresets: Boolean = false,
    val teleprompterText: String = "",
    val showTextEditorScreen: Boolean = false,
    val toastMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs = AuphonicPreferences(context)
    private val recorder = AudioRecorder(context)
    private val player = AudioPlayer(context)
    private val apiClient = AuphonicApiClient()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var bannerJob: Job? = null
    private var playbackJob: Job? = null
    private var activeRecordingFile: File? = null
    private var lastBeepPlayedSec: Long = -1L

    private var recordingStartTimeMs: Long = 0L
    private var totalPausedDurationMs: Long = 0L
    private var pauseStartTimeMs: Long = 0L

    init {
        loadCredentials()
        viewModelScope.launch {
            delay(3000)
            _uiState.value = _uiState.value.copy(showSplashScreen = false)
        }
    }

    fun loadCredentials() {
        val apiKey = prefs.getApiKey()
        val cachedPresets = parsePresetsJson(prefs.getSavedPresetsJson())

        _uiState.value = _uiState.value.copy(
            apiKey = apiKey,
            selectedPresetUuid = prefs.getSelectedPresetUuid(),
            isCustomPreset = prefs.getIsCustomPreset(),
            customPresetInput = prefs.getCustomPresetInput(),
            trimAudioEnabled = prefs.getTrimAudioEnabled(),
            presetsList = cachedPresets,
            recBitrateKbps = prefs.getRecordingBitrateKbps(),
            recChannels = prefs.getRecordingChannels(),
            recSampleRateHz = prefs.getRecordingSampleRateHz(),
            recNamePrefix = prefs.getRecordingNamePrefix(),
            processedSuffix = prefs.getProcessedSuffix(),
            recInputDeviceId = prefs.getRecordingInputDeviceId(),
            recInputDeviceName = prefs.getRecordingInputDeviceName(),
            teleprompterText = prefs.getTeleprompterText(),
            autoSaveOriginalToDownloads = prefs.getAutoSaveOriginalToDownloads(),
            timeWarningMode = prefs.getTimeWarningMode(),
            timeWarningCustomMins = prefs.getTimeWarningCustomMinutes(),
            isAutoScrollEnabled = prefs.getAutoScrollEnabled(),
            autoScrollSpeed = prefs.getAutoScrollSpeed()
        )

        if (apiKey.isNotBlank()) {
            fetchPresetsFromApi(apiKey)
        }
    }

    fun updateAutoSaveOriginalToDownloads(enabled: Boolean) {
        prefs.saveAutoSaveOriginalToDownloads(enabled)
        _uiState.value = _uiState.value.copy(autoSaveOriginalToDownloads = enabled)
    }

    fun updateAutoScrollEnabled(enabled: Boolean) {
        prefs.saveAutoScrollEnabled(enabled)
        _uiState.value = _uiState.value.copy(isAutoScrollEnabled = enabled)
    }

    fun updateAutoScrollSpeed(speed: Float) {
        prefs.saveAutoScrollSpeed(speed)
        _uiState.value = _uiState.value.copy(autoScrollSpeed = speed)
    }

    fun saveTimeWarningSettings(mode: Int, customMins: Int) {
        prefs.saveTimeWarningMode(mode)
        prefs.saveTimeWarningCustomMinutes(customMins)
        _uiState.value = _uiState.value.copy(
            timeWarningMode = mode,
            timeWarningCustomMins = customMins,
            showTimeWarningDialog = false
        )
        showToast("Avisos de tiempo guardados")
    }

    fun toggleTimeWarningDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showTimeWarningDialog = show)
    }

    fun toggleAboutDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAboutDialog = show)
    }

    fun dismissTimeWarningBanner() {
        bannerJob?.cancel()
        _uiState.value = _uiState.value.copy(timeWarningBannerText = null)
    }

    fun fetchPresetsFromApi(apiKey: String = _uiState.value.apiKey) {
        if (apiKey.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPresets = true)
            val result = apiClient.fetchPresets(apiKey)
            if (result.isSuccess) {
                val presets = result.getOrNull() ?: emptyList()
                _uiState.value = _uiState.value.copy(
                    presetsList = presets,
                    isLoadingPresets = false
                )
                val jsonArray = JSONArray()
                presets.forEach { p ->
                    val obj = JSONObject()
                    obj.put("uuid", p.uuid)
                    obj.put("name", p.name)
                    jsonArray.put(obj)
                }
                prefs.saveSavedPresetsJson(jsonArray.toString())
            } else {
                _uiState.value = _uiState.value.copy(isLoadingPresets = false)
            }
        }
    }

    fun saveAuphonicSettings(
        apiKey: String,
        selectedPresetUuid: String,
        isCustomPreset: Boolean,
        customPresetInput: String,
        trimAudioEnabled: Boolean
    ) {
        prefs.saveApiKey(apiKey)
        prefs.saveSelectedPresetUuid(selectedPresetUuid)
        prefs.saveIsCustomPreset(isCustomPreset)
        prefs.saveCustomPresetInput(customPresetInput)
        prefs.saveTrimAudioEnabled(trimAudioEnabled)

        _uiState.value = _uiState.value.copy(
            apiKey = apiKey,
            selectedPresetUuid = selectedPresetUuid,
            isCustomPreset = isCustomPreset,
            customPresetInput = customPresetInput,
            trimAudioEnabled = trimAudioEnabled,
            showCredentialsDialog = false
        )
        showToast("Configuración de Auphonic guardada")
    }

    fun toggleCredentialsDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCredentialsDialog = show)
        if (show && _uiState.value.apiKey.isNotBlank()) {
            fetchPresetsFromApi()
        }
    }

    fun saveRecordingSettings(
        bitrate: Int,
        channels: Int,
        sampleRate: Int,
        prefix: String,
        suffix: String,
        inputDeviceId: Int,
        inputDeviceName: String
    ) {
        prefs.saveRecordingBitrateKbps(bitrate)
        prefs.saveRecordingChannels(channels)
        prefs.saveRecordingSampleRateHz(sampleRate)
        prefs.saveRecordingNamePrefix(prefix)
        prefs.saveProcessedSuffix(suffix)
        prefs.saveRecordingInputDeviceId(inputDeviceId)
        prefs.saveRecordingInputDeviceName(inputDeviceName)

        _uiState.value = _uiState.value.copy(
            recBitrateKbps = bitrate,
            recChannels = channels,
            recSampleRateHz = sampleRate,
            recNamePrefix = prefix,
            processedSuffix = suffix,
            recInputDeviceId = inputDeviceId,
            recInputDeviceName = inputDeviceName,
            showRecordingSettingsDialog = false
        )
        showToast("Opciones de grabación guardadas")
    }

    fun toggleRecordingSettingsDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showRecordingSettingsDialog = show)
    }

    fun dismissNoScriptDialog() {
        _uiState.value = _uiState.value.copy(showNoScriptDialog = false)
    }

    fun startRecordingWithoutScript() {
        _uiState.value = _uiState.value.copy(showNoScriptDialog = false)
        startRecording()
    }

    fun updateTeleprompterText(text: String) {
        prefs.saveTeleprompterText(text)
        _uiState.value = _uiState.value.copy(teleprompterText = text)
    }

    fun openTextEditor(show: Boolean) {
        _uiState.value = _uiState.value.copy(showTextEditorScreen = show)
    }

    fun closeAuphonicResultScreen() {
        _uiState.value = _uiState.value.copy(showAuphonicResultScreen = false)
    }

    fun selectAudioSource(source: AudioSourceSelection) {
        if (_uiState.value.selectedAudioSource == source) return

        val wasOriginalPlaying = _uiState.value.isOriginalPlaying
        val wasProcessedPlaying = _uiState.value.isProcessedPlaying
        val wasPlaying = wasOriginalPlaying || wasProcessedPlaying

        val currentPos = player.getCurrentPosition().coerceAtLeast(0)

        player.stop()

        _uiState.value = _uiState.value.copy(
            selectedAudioSource = source,
            isOriginalPlaying = false,
            isProcessedPlaying = false
        )

        val targetFile = if (source == AudioSourceSelection.ORIGINAL) {
            _uiState.value.originalAudioFile
        } else {
            _uiState.value.processedAudioFile
        }

        if (targetFile != null && targetFile.exists()) {
            if (source == AudioSourceSelection.ORIGINAL) {
                _uiState.value = _uiState.value.copy(originalPlaybackPosMs = currentPos)
            } else {
                _uiState.value = _uiState.value.copy(processedPlaybackPosMs = currentPos)
            }

            if (wasPlaying) {
                val isOriginal = (source == AudioSourceSelection.ORIGINAL)
                if (isOriginal) {
                    _uiState.value = _uiState.value.copy(isOriginalPlaying = true)
                } else {
                    _uiState.value = _uiState.value.copy(isProcessedPlaying = true)
                }

                player.play(targetFile) {
                    if (isOriginal) {
                        _uiState.value = _uiState.value.copy(
                            isOriginalPlaying = false,
                            originalPlaybackPosMs = _uiState.value.originalDurationMs.toInt()
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isProcessedPlaying = false,
                            processedPlaybackPosMs = _uiState.value.processedDurationMs.toInt()
                        )
                    }
                    stopPlaybackJob()
                }

                player.seekTo(currentPos)
                startPlaybackJob(isOriginal = isOriginal)
            }
        }
    }

    fun onRecordButtonClicked(hasPermission: Boolean, onRequestPermission: () -> Unit) {
        if (!hasPermission) {
            onRequestPermission()
            return
        }

        if (_uiState.value.teleprompterText.trim().isEmpty()) {
            _uiState.value = _uiState.value.copy(showNoScriptDialog = true)
            return
        }

        startRecording()
    }

    private fun pauseSystemMediaPlayback() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                audioManager.dispatchMediaKeyEvent(down)
                audioManager.dispatchMediaKeyEvent(up)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    audioManager.requestAudioFocus(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startRecording() {
        stopPlayback()
        pauseSystemMediaPlayback()

        // Iniciar servicio en primer plano con WakeLock para asegurar grabación con pantalla apagada
        AudioRecordingService.startService(context)

        val prefix = _uiState.value.recNamePrefix.ifBlank { "Grabacion_" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${prefix}${timestamp}.m4a"
        val file = File(context.cacheDir, fileName)
        activeRecordingFile = file

        recordingStartTimeMs = System.currentTimeMillis()
        totalPausedDurationMs = 0L
        pauseStartTimeMs = 0L

        // Usar estrictamente la fuente seleccionada manualmente por el usuario en las opciones
        val success = recorder.startRecording(
            outputFile = file,
            bitRateKbps = _uiState.value.recBitrateKbps,
            channels = _uiState.value.recChannels,
            sampleRateHz = _uiState.value.recSampleRateHz,
            inputDeviceId = _uiState.value.recInputDeviceId
        )

        if (success) {
            _uiState.value = _uiState.value.copy(
                recordingStatus = RecordingStatus.Recording,
                recordingTimeSeconds = 0L,
                originalAudioFile = null,
                originalDurationMs = 0L,
                processedAudioFile = null,
                processedDurationMs = 0L,
                selectedAudioSource = AudioSourceSelection.ORIGINAL,
                auphonicState = AuphonicProcessState.Idle,
                timeWarningBannerText = null
            )

            startTimer()
        } else {
            AudioRecordingService.stopService(context)
            showToast("Error al iniciar la grabación")
        }
    }

    fun pauseRecording() {
        if (recorder.pauseRecording()) {
            pauseStartTimeMs = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Paused)
        }
    }

    fun resumeRecording() {
        if (recorder.resumeRecording()) {
            if (pauseStartTimeMs > 0) {
                totalPausedDurationMs += (System.currentTimeMillis() - pauseStartTimeMs)
                pauseStartTimeMs = 0L
            }
            _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Recording)
        }
    }

    fun stopRecording() {
        stopTimer()
        bannerJob?.cancel()
        val recordedFile = recorder.stopRecording()

        // Detener servicio en primer plano y liberar WakeLock
        AudioRecordingService.stopService(context)

        recordingStartTimeMs = 0L
        totalPausedDurationMs = 0L
        pauseStartTimeMs = 0L

        if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
            val durationMs = AudioTrimmer.getAudioDurationMs(recordedFile)
            _uiState.value = _uiState.value.copy(
                recordingStatus = RecordingStatus.Idle,
                originalAudioFile = recordedFile,
                originalDurationMs = durationMs,
                processedAudioFile = null,
                processedDurationMs = 0L,
                selectedAudioSource = AudioSourceSelection.ORIGINAL,
                auphonicState = AuphonicProcessState.Idle,
                timeWarningBannerText = null
            )

            // Solo guardar automáticamente en Descargas si el check está activado
            if (_uiState.value.autoSaveOriginalToDownloads) {
                saveSelectedFileToDownloads()
            } else {
                showToast("Grabación original guardada localmente")
            }
        } else {
            _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Idle, timeWarningBannerText = null)
            showToast("La grabación fue descartada o no contiene audio")
        }
    }

    fun loadAudioFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                stopPlayback()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val originalName = getFileNameFromUri(context, uri)
                val ext = originalName?.let { File(it).extension }?.takeIf { it.isNotBlank() } ?: "mp3"
                val baseName = originalName?.let { File(it).nameWithoutExtension } ?: "Importado_$timestamp"

                val destFile = File(context.cacheDir, "Importado_${baseName}.${ext}")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (destFile.exists() && destFile.length() > 0) {
                    val durationMs = AudioTrimmer.getAudioDurationMs(destFile)
                    _uiState.value = _uiState.value.copy(
                        originalAudioFile = destFile,
                        originalDurationMs = durationMs,
                        processedAudioFile = null,
                        processedDurationMs = 0L,
                        selectedAudioSource = AudioSourceSelection.ORIGINAL,
                        auphonicState = AuphonicProcessState.Idle
                    )
                    showToast("Audio cargado: ${destFile.name.removePrefix("Importado_")}")
                } else {
                    showToast("No se pudo leer el archivo de audio")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Error al cargar el archivo de audio: ${e.message}")
            }
        }
    }

    fun convertOriginalToMp3() {
        val fileToConvert = _uiState.value.originalAudioFile
        if (fileToConvert == null || !fileToConvert.exists()) {
            showToast("No hay archivo original cargado para convertir")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                auphonicState = AuphonicProcessState.Working("Convertiendo archivo M4A a MP3...")
            )

            val baseOriginal = fileToConvert.nameWithoutExtension.removePrefix("Importado_")
            val outputFile = File(context.cacheDir, "Local_${baseOriginal}.mp3")

            val result = Mp3Transcoder.convertM4aToMp3(
                inputFile = fileToConvert,
                outputFile = outputFile,
                targetBitrateKbps = _uiState.value.recBitrateKbps
            ) { percent ->
                _uiState.value = _uiState.value.copy(
                    auphonicState = AuphonicProcessState.Working("Convertiendo a MP3 ($percent%)...", percent)
                )
            }

            if (result.isSuccess) {
                val convertedFile = result.getOrNull()!!
                val targetFileName = "${baseOriginal}.mp3"

                saveFileToDownloadsDirect(convertedFile, targetFileName, "audio/mpeg")

                _uiState.value = _uiState.value.copy(
                    auphonicState = AuphonicProcessState.Idle
                )
                showToast("Guardado en Descargas: $targetFileName")
            } else {
                val error = result.exceptionOrNull()?.message ?: "Error al convertir a MP3"
                _uiState.value = _uiState.value.copy(
                    auphonicState = AuphonicProcessState.Error("Error en conversión MP3: $error")
                )
            }
        }
    }

    private suspend fun saveFileToDownloadsDirect(
        fileToSave: File,
        targetFileName: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        fileToSave.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val destFile = File(downloadsDir, targetFileName)
                fileToSave.copyTo(destFile, overwrite = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processWithAuphonic() {
        val fileToProcess = _uiState.value.originalAudioFile
        if (fileToProcess == null || !fileToProcess.exists()) {
            showToast("No hay ningún archivo de audio cargado para enviar")
            return
        }

        if (!prefs.isConfigured()) {
            _uiState.value = _uiState.value.copy(showCredentialsDialog = true)
            showToast("Por favor configure la API Key y Preset de Auphonic")
            return
        }

        val apiKey = prefs.getApiKey()
        val effectivePreset = prefs.getEffectivePreset()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                auphonicState = AuphonicProcessState.Working("Iniciando envío a Auphonic...")
            )

            // Step 1: Subir y procesar en Auphonic
            val result = apiClient.startProductionAndPoll(
                apiKey = apiKey,
                preset = effectivePreset,
                audioFile = fileToProcess
            ) { status ->
                when (status) {
                    is ProductionStatus.Progress -> {
                        _uiState.value = _uiState.value.copy(
                            auphonicState = AuphonicProcessState.Working(status.statusString)
                        )
                    }
                    is ProductionStatus.Success -> {
                        _uiState.value = _uiState.value.copy(
                            auphonicState = AuphonicProcessState.Working("Descargando audio procesado...")
                        )
                    }
                    is ProductionStatus.Error -> {
                        _uiState.value = _uiState.value.copy(
                            auphonicState = AuphonicProcessState.Error(status.message)
                        )
                    }
                }
            }

            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Error desconocido"
                _uiState.value = _uiState.value.copy(
                    auphonicState = AuphonicProcessState.Error(error)
                )
                return@launch
            }

            val downloadUrl = result.getOrNull() ?: ""

            // Step 2: Descargar audio procesado
            val rawProcessedFile = File(context.cacheDir, "auphonic_raw_${fileToProcess.nameWithoutExtension}.mp3")
            val downloadResult = apiClient.downloadProcessedAudio(
                apiKey = apiKey,
                downloadUrl = downloadUrl,
                outputFile = rawProcessedFile
            ) { percent ->
                _uiState.value = _uiState.value.copy(
                    auphonicState = AuphonicProcessState.Working("Descargando audio ($percent%)...", percent)
                )
            }

            if (downloadResult.isFailure) {
                val error = downloadResult.exceptionOrNull()?.message ?: "Error al descargar el audio"
                _uiState.value = _uiState.value.copy(
                    auphonicState = AuphonicProcessState.Error(error)
                )
                return@launch
            }

            // Step 3: Abrir ventana dedicada para ajuste de recorte
            _uiState.value = _uiState.value.copy(
                rawAuphonicFile = rawProcessedFile,
                showAuphonicResultScreen = true,
                auphonicState = AuphonicProcessState.Working("Audio recibido. Selecciona la opción de recorte...")
            )
        }
    }

    fun finalizeAuphonicResult(trimMode: TrimMode) {
        val originalFile = _uiState.value.originalAudioFile
        val rawProcessedFile = _uiState.value.rawAuphonicFile

        if (originalFile == null || rawProcessedFile == null || !rawProcessedFile.exists()) {
            showToast("No se pudo procesar el archivo recibido")
            _uiState.value = _uiState.value.copy(showAuphonicResultScreen = false)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                auphonicState = AuphonicProcessState.Working("Aplicando recorte y guardando...")
            )

            val baseOriginal = originalFile.nameWithoutExtension.removePrefix("Importado_")
            val finalOutputFile = File(context.cacheDir, "Auphonic_${baseOriginal}.mp3")
            val trimResult = AudioTrimmer.trimAudioToTargetDuration(
                originalFile = originalFile,
                processedFile = rawProcessedFile,
                outputFile = finalOutputFile,
                trimMode = trimMode
            )

            if (trimResult.isSuccess) {
                val finalFile = trimResult.getOrNull()!!
                val finalDurationMs = AudioTrimmer.getAudioDurationMs(finalFile)
                val originalDurationMs = _uiState.value.originalDurationMs.takeIf { it > 0 }
                    ?: AudioTrimmer.getAudioDurationMs(originalFile)

                _uiState.value = _uiState.value.copy(
                    processedAudioFile = finalFile,
                    processedDurationMs = finalDurationMs,
                    selectedAudioSource = AudioSourceSelection.PROCESSED,
                    showAuphonicResultScreen = false,
                    auphonicState = AuphonicProcessState.Success(
                        originalFile = originalFile,
                        processedFile = finalFile,
                        originalDurationMs = originalDurationMs,
                        processedDurationMs = finalDurationMs
                    )
                )

                // Guardado automático del resultado procesado en Descargas
                saveSelectedFileToDownloads()
                showToast("¡Audio de Auphonic recortado y guardado con éxito!")
            } else {
                val error = trimResult.exceptionOrNull()?.message ?: "Error al recortar audio"
                _uiState.value = _uiState.value.copy(
                    showAuphonicResultScreen = false,
                    auphonicState = AuphonicProcessState.Error(error)
                )
            }
        }
    }

    fun saveSelectedFileToDownloads() {
        val isProcessed = _uiState.value.selectedAudioSource == AudioSourceSelection.PROCESSED
        val fileToSave = if (isProcessed) _uiState.value.processedAudioFile else _uiState.value.originalAudioFile

        if (fileToSave == null || !fileToSave.exists()) {
            showToast("No hay archivo para guardar")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseOriginal = _uiState.value.originalAudioFile?.nameWithoutExtension
                    ?.removePrefix("Importado_")
                    ?: fileToSave.nameWithoutExtension
                        .removePrefix("Importado_")
                        .removePrefix("Auphonic_")
                        .removePrefix("auphonic_raw_")

                val suffix = _uiState.value.processedSuffix.ifBlank { "_procesado" }
                val ext = fileToSave.extension.ifBlank { if (isProcessed) "mp3" else "m4a" }

                val targetFileName = if (isProcessed) {
                    "${baseOriginal}${suffix}.mp3"
                } else {
                    "${baseOriginal}.${ext}"
                }

                val mimeType = if (ext.equals("mp3", ignoreCase = true)) "audio/mpeg" else "audio/mp4"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { output ->
                            fileToSave.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        showToast("Guardado en Descargas: $targetFileName")
                    } else {
                        showToast("Error al guardar archivo en Descargas")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, targetFileName)
                    fileToSave.copyTo(destFile, overwrite = true)
                    showToast("Guardado en Descargas: ${destFile.name}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Error al guardar archivo: ${e.message}")
            }
        }
    }

    fun playSelectedAudio() {
        val isOriginal = _uiState.value.selectedAudioSource == AudioSourceSelection.ORIGINAL
        val fileToPlay = if (isOriginal) _uiState.value.originalAudioFile else _uiState.value.processedAudioFile

        if (fileToPlay == null || !fileToPlay.exists()) return

        val currentPos = if (isOriginal) _uiState.value.originalPlaybackPosMs else _uiState.value.processedPlaybackPosMs
        val totalDuration = if (isOriginal) _uiState.value.originalDurationMs else _uiState.value.processedDurationMs

        val isNearEnd = totalDuration > 0 && currentPos >= totalDuration - 300

        if (isNearEnd) {
            if (isOriginal) {
                _uiState.value = _uiState.value.copy(originalPlaybackPosMs = 0)
            } else {
                _uiState.value = _uiState.value.copy(processedPlaybackPosMs = 0)
            }
        }

        player.stop()
        if (isOriginal) {
            _uiState.value = _uiState.value.copy(isOriginalPlaying = true, isProcessedPlaying = false)
        } else {
            _uiState.value = _uiState.value.copy(isOriginalPlaying = false, isProcessedPlaying = true)
        }

        player.play(fileToPlay) {
            if (isOriginal) {
                _uiState.value = _uiState.value.copy(
                    isOriginalPlaying = false,
                    originalPlaybackPosMs = _uiState.value.originalDurationMs.toInt()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessedPlaying = false,
                    processedPlaybackPosMs = _uiState.value.processedDurationMs.toInt()
                )
            }
            stopPlaybackJob()
        }

        if (isNearEnd) {
            player.seekTo(0)
        }

        startPlaybackJob(isOriginal = isOriginal)
    }

    fun pauseSelectedAudio() {
        player.pause()
        _uiState.value = _uiState.value.copy(
            isOriginalPlaying = false,
            isProcessedPlaying = false
        )
        stopPlaybackJob()
    }

    fun seekSelectedTo(posMs: Int) {
        player.seekTo(posMs)
        if (_uiState.value.selectedAudioSource == AudioSourceSelection.ORIGINAL) {
            _uiState.value = _uiState.value.copy(originalPlaybackPosMs = posMs)
        } else {
            _uiState.value = _uiState.value.copy(processedPlaybackPosMs = posMs)
        }
    }

    fun stopPlayback() {
        player.stop()
        _uiState.value = _uiState.value.copy(
            isOriginalPlaying = false,
            isProcessedPlaying = false
        )
        stopPlaybackJob()
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { File(it).name }
        }
        return name
    }

    private fun parsePresetsJson(jsonStr: String): List<AuphonicPreset> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<AuphonicPreset>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uuid = obj.optString("uuid")
                val name = obj.optString("name")
                if (uuid.isNotBlank()) {
                    list.add(AuphonicPreset(uuid, name))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun showToast(msg: String) {
        _uiState.value = _uiState.value.copy(toastMessage = msg)
    }

    private fun triggerTimeWarning(sec: Long, bannerText: String, freqHz: Int) {
        lastBeepPlayedSec = sec
        _uiState.value = _uiState.value.copy(timeWarningBannerText = bannerText)

        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(2500)
            _uiState.value = _uiState.value.copy(timeWarningBannerText = null)
        }

        // Comprobar EN EL MOMENTO EXACTO DEL AVISO si hay auriculares conectados
        val isHeadsetPresent = TimeWarningManager.isHeadsetConnected(context)
        if (isHeadsetPresent) {
            viewModelScope.launch { TimeWarningManager.playBeep(freqHz) }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        lastBeepPlayedSec = -1L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(200)
                if (_uiState.value.recordingStatus == RecordingStatus.Recording) {
                    val now = System.currentTimeMillis()
                    val elapsedMs = (now - recordingStartTimeMs - totalPausedDurationMs).coerceAtLeast(0L)
                    val elapsedSec = elapsedMs / 1000L
                    val amp = recorder.getMaxAmplitude()
                    _uiState.value = _uiState.value.copy(
                        recordingTimeSeconds = elapsedSec,
                        currentAmplitude = amp
                    )

                    // Lógica de avisos de tiempo (Globo flotante siempre + pitido si hay auriculares)
                    val mode = _uiState.value.timeWarningMode
                    val targetSec = when (mode) {
                        1 -> 60L
                        2 -> 600L
                        3 -> _uiState.value.timeWarningCustomMins * 60L
                        else -> 0L
                    }

                    if (targetSec > 0 && elapsedSec != lastBeepPlayedSec) {
                        val remainingSec = targetSec - elapsedSec
                        when (remainingSec) {
                            20L -> triggerTimeWarning(elapsedSec, "⏱️ Faltan 20 segundos", 800)
                            10L -> triggerTimeWarning(elapsedSec, "⏱️ Faltan 10 segundos", 1000)
                            5L  -> triggerTimeWarning(elapsedSec, "⏱️ Faltan 5 segundos", 1200)
                            2L  -> triggerTimeWarning(elapsedSec, "⏱️ ¡Faltan 2 segundos!", 1500)
                        }
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun startPlaybackJob(isOriginal: Boolean) {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (true) {
                delay(200)
                val pos = player.getCurrentPosition()
                if (isOriginal) {
                    _uiState.value = _uiState.value.copy(originalPlaybackPosMs = pos)
                } else {
                    _uiState.value = _uiState.value.copy(processedPlaybackPosMs = pos)
                }
            }
        }
    }

    private fun stopPlaybackJob() {
        playbackJob?.cancel()
        playbackJob = null
    }

    override fun onCleared() {
        super.onCleared()
        recorder.stopRecording()
        player.stop()
    }
}
