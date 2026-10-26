package com.prestocast.grabador.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.prestocast.grabador.audio.AudioInputManager
import com.prestocast.grabador.audio.AudioPlayer
import com.prestocast.grabador.audio.AudioTrimmer
import com.prestocast.grabador.audio.TrimMode
import com.prestocast.grabador.data.AuphonicPreset
import com.prestocast.grabador.ui.theme.AuphonicGreen
import com.prestocast.grabador.ui.theme.AuphonicOrange
import com.prestocast.grabador.ui.theme.RecordingRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

val SYSTEM_PRESETS = listOf(
    AuphonicPreset("auphonic_cleaner", "Auphonic Cleaner (Limpieza y nivelación)"),
    AuphonicPreset("default", "Preset por defecto de la cuenta Auphonic"),
    AuphonicPreset("speech_isolation", "Aislamiento de Voz (Speech Isolation)")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasRecordPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Splash Screen vistosa a toda pantalla dentro del espacio de la app (se cierra sola tras 3.0s)
    if (uiState.showSplashScreen) {
        SplashScreenContent()
        return
    }

    // Toast notification handler
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Scroll automático hacia abajo cuando cambia el estado del proceso de Auphonic
    LaunchedEffect(uiState.auphonicState) {
        if (uiState.auphonicState !is AuphonicProcessState.Idle) {
            delay(250) // Esperar recomposición y renderizado del nuevo bloque
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // File picker launcher
    val openAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadAudioFromUri(it) }
    }

    // Pantalla dedicada para resultado y opciones de recorte de Auphonic
    if (uiState.showAuphonicResultScreen && uiState.rawAuphonicFile != null) {
        val cleanTargetName = getCleanDisplayFileName(
            file = uiState.originalAudioFile ?: uiState.rawAuphonicFile,
            isProcessed = true,
            suffix = uiState.processedSuffix
        )

        AuphonicResultScreen(
            rawFile = uiState.rawAuphonicFile!!,
            originalDurationMs = uiState.originalDurationMs,
            targetFileName = cleanTargetName,
            onBack = { viewModel.closeAuphonicResultScreen() },
            onApplyAndSave = { trimMode ->
                viewModel.finalizeAuphonicResult(trimMode)
            }
        )
        return
    }

    // Si la pantalla de edición de texto está activa, se muestra esa pantalla independiente
    if (uiState.showTextEditorScreen) {
        TextEditorScreen(
            initialText = uiState.teleprompterText,
            onBack = { viewModel.openTextEditor(false) },
            onSave = { newText ->
                viewModel.updateTeleprompterText(newText)
                viewModel.openTextEditor(false)
            },
            onSaveAndRecord = { newText ->
                viewModel.updateTeleprompterText(newText)
                viewModel.openTextEditor(false)
                viewModel.startRecording()
            }
        )
        return
    }

    val isPlayingAny = uiState.isOriginalPlaying || uiState.isProcessedPlaying
    val isIdle = uiState.recordingStatus == RecordingStatus.Idle

    Box(modifier = Modifier.fillMaxSize()) {
        // Capa principal Scaffold (Permanece 100% fija e inamovible)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Grabador Prestocast",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.openTextEditor(true) }) {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = "Editar Texto / Guión"
                            )
                        }

                        // Botón Play / Pause estricto a nivel de sistema (eventos multimedia del sistema)
                        IconButton(
                            onClick = {
                                sendSystemMediaPlayPause(context)
                            },
                            enabled = true
                        ) {
                            Icon(
                                imageVector = if (isPlayingAny) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                contentDescription = "Reproducir / Pausar Audio (Sistema)",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(onClick = { viewModel.toggleRecordingSettingsDialog(true) }) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Opciones de Grabación"
                            )
                        }

                        IconButton(onClick = { viewModel.toggleCredentialsDialog(true) }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Configuración de Auphonic"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(12.dp)
                    .then(
                        if (isIdle) {
                            Modifier.verticalScroll(scrollState)
                        } else Modifier
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quality Card & Auto-Save Checkbox (Se ocultan dinámicamente al grabar)
                AnimatedVisibility(visible = isIdle) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                val channelsText = if (uiState.recChannels == 2) "Stereo" else "Mono"
                                val sampleRateKHz = String.format(Locale.getDefault(), "%.1f", uiState.recSampleRateHz / 1000.0)
                                Text(
                                    text = "Ajustes: $channelsText ${uiState.recBitrateKbps} kbps ${sampleRateKHz}kHz M4A",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 4.dp)
                                )
                                Text(
                                    text = "Fuente de entrada: ${uiState.recInputDeviceName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Checkbox Guardado Automático de Grabación Original en Descargas
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = uiState.autoSaveOriginalToDownloads,
                                    onCheckedChange = { viewModel.updateAutoSaveOriginalToDownloads(it) },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Guardar automáticamente grabación original en Descargas",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }
                }

                // Recorder Control Card (Se expande hacia abajo al grabar ocupando toda la pantalla)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isIdle && uiState.teleprompterText.isNotBlank()) {
                                Modifier.weight(1f)
                            } else Modifier
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Grabadora de Audio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            TextButton(
                                onClick = { viewModel.openTextEditor(true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (uiState.teleprompterText.isBlank()) "Añadir Guión" else "Editar Guión",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Timer & Controls compact layout
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Timer Display
                            val seconds = uiState.recordingTimeSeconds
                            val formattedTime = formatTime(seconds * 1000L)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (uiState.recordingStatus == RecordingStatus.Recording) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(RecordingRed)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = formattedTime,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.recordingStatus == RecordingStatus.Recording) RecordingRed else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Recording Buttons (Compact)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (uiState.recordingStatus) {
                                    RecordingStatus.Idle -> {
                                        FloatingActionButton(
                                            onClick = {
                                                viewModel.onRecordButtonClicked(hasRecordPermission, onRequestPermission)
                                            },
                                            containerColor = RecordingRed,
                                            contentColor = Color.White,
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Mic,
                                                contentDescription = "Grabar",
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                    RecordingStatus.Recording -> {
                                        IconButton(
                                            onClick = { viewModel.pauseRecording() },
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.secondaryContainer,
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(Icons.Default.Pause, contentDescription = "Pausar")
                                        }

                                        FloatingActionButton(
                                            onClick = { viewModel.stopRecording() },
                                            containerColor = RecordingRed,
                                            contentColor = Color.White,
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = "Detener",
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                    RecordingStatus.Paused -> {
                                        IconButton(
                                            onClick = { viewModel.resumeRecording() },
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Reanudar")
                                        }

                                        FloatingActionButton(
                                            onClick = { viewModel.stopRecording() },
                                            containerColor = RecordingRed,
                                            contentColor = Color.White,
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = "Detener",
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Teleprompter Script Display when Recording/Paused (Se expande hasta abajo del todo)
                        if (!isIdle && uiState.teleprompterText.isNotBlank()) {
                            HorizontalDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Guión / Texto en curso:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = uiState.teleprompterText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontSize = 20.sp,
                                        lineHeight = 28.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Open External Audio Button (Se oculta dinámicamente al grabar)
                AnimatedVisibility(visible = isIdle) {
                    OutlinedButton(
                        onClick = { openAudioLauncher.launch("audio/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Abrir archivo de audio local")
                    }
                }

                // Audio Player Section with Selector (Se muestra en reposo)
                val hasOriginal = uiState.originalAudioFile != null && uiState.originalAudioFile!!.exists()
                val hasProcessed = uiState.processedAudioFile != null && uiState.processedAudioFile!!.exists()

                if (isIdle && (hasOriginal || hasProcessed)) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Reproductor y Selección de Audio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // Selector TabRow / Segmented Buttons
                            TabRow(
                                selectedTabIndex = if (uiState.selectedAudioSource == AudioSourceSelection.ORIGINAL) 0 else 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                Tab(
                                    selected = uiState.selectedAudioSource == AudioSourceSelection.ORIGINAL,
                                    onClick = { viewModel.selectAudioSource(AudioSourceSelection.ORIGINAL) },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.AudioFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "Original",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                )

                                Tab(
                                    selected = uiState.selectedAudioSource == AudioSourceSelection.PROCESSED,
                                    onClick = {
                                        if (hasProcessed) {
                                            viewModel.selectAudioSource(AudioSourceSelection.PROCESSED)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Procesa primero el audio con Auphonic o conviértelo a MP3",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    enabled = hasProcessed,
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = if (hasProcessed) AuphonicGreen else Color.Gray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "Procesado MP3",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                )
                            }

                            // Selected Audio Information
                            val isOriginalSelected = uiState.selectedAudioSource == AudioSourceSelection.ORIGINAL
                            val currentFile = if (isOriginalSelected) uiState.originalAudioFile else uiState.processedAudioFile
                            val currentDurationMs = if (isOriginalSelected) uiState.originalDurationMs else uiState.processedDurationMs
                            val isPlaying = if (isOriginalSelected) uiState.isOriginalPlaying else uiState.isProcessedPlaying
                            val currentPosMs = if (isOriginalSelected) uiState.originalPlaybackPosMs else uiState.processedPlaybackPosMs

                            if (currentFile != null && currentFile.exists()) {
                                val cleanDisplayName = getCleanDisplayFileName(
                                    file = currentFile,
                                    isProcessed = !isOriginalSelected,
                                    suffix = uiState.processedSuffix
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isOriginalSelected) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.primaryContainer,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (isOriginalSelected) "Fichero Seleccionado: Audio Original" else "Fichero Seleccionado: Audio Procesado (MP3)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isOriginalSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = cleanDisplayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Duración exactitud: ${formatTime(currentDurationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    // Playback Bar Controls
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    viewModel.pauseSelectedAudio()
                                                } else {
                                                    viewModel.playSelectedAudio()
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Reproducir / Pausar",
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }

                                        val floatPos = currentPosMs.toFloat()
                                        val floatTotal = currentDurationMs.coerceAtLeast(1L).toFloat()
                                        Slider(
                                            value = floatPos.coerceIn(0f, floatTotal),
                                            onValueChange = { viewModel.seekSelectedTo(it.toInt()) },
                                            valueRange = 0f..floatTotal,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = formatTime(currentPosMs.toLong()),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = formatTime(currentDurationMs),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    // Action buttons inside Player Card: Compartir, Guardar, Convertir a MP3 y Enviar a Auphonic
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { shareAudioFile(context, currentFile) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Compartir", fontSize = 12.sp, maxLines = 1)
                                        }

                                        Button(
                                            onClick = { viewModel.saveSelectedFileToDownloads() },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Guardar", fontSize = 12.sp, maxLines = 1)
                                        }
                                    }

                                    if (hasOriginal) {
                                        Button(
                                            onClick = { viewModel.convertOriginalToMp3() },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                        ) {
                                            Icon(
                                                Icons.Default.Transform,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                            Text("Convertir a MP3", fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                viewModel.processWithAuphonic()
                                                coroutineScope.launch {
                                                    delay(300)
                                                    scrollState.animateScrollTo(scrollState.maxValue)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = AuphonicOrange)
                                        ) {
                                            Icon(
                                                Icons.Default.CloudUpload,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                            Text("Enviar a Auphonic.com (Codificar y Nivelar MP3)", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Auphonic Status Card (Working/Error/Success)
                when (val state = uiState.auphonicState) {
                    is AuphonicProcessState.Working -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Procesando Audio",
                                    fontWeight = FontWeight.Bold
                                )
                                if (state.progressPercent != null) {
                                    LinearProgressIndicator(
                                        progress = { state.progressPercent / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    is AuphonicProcessState.Error -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = "Error de Procesamiento",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    is AuphonicProcessState.Success -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AuphonicGreen,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = "¡Audio Procesado a MP3 con Éxito!",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Text(
                                    text = "Duración original: ${formatTime(state.originalDurationMs)} | Duración MP3: ${formatTime(state.processedDurationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuphonicGreen
                                )
                            }
                        }
                    }

                    else -> {}
                }
            }
        }

        // Globo flotante superpuesto en TopCenter (Flota POR ENCIMA del Scaffold sin desplazar ningún elemento)
        AnimatedVisibility(
            visible = uiState.timeWarningBannerText != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                onClick = { viewModel.dismissTimeWarningBanner() },
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.AccessAlarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = uiState.timeWarningBannerText ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // Dialogo Confirmacion Grabar sin Guion
    if (uiState.showNoScriptDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNoScriptDialog() },
            title = { Text("Grabar sin Guión") },
            text = {
                Text(
                    "No has introducido ningún guión. ¿Deseas iniciar la grabación sin guión o prefieres escribir uno primero?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.startRecordingWithoutScript() },
                    colors = ButtonDefaults.buttonColors(containerColor = RecordingRed)
                ) {
                    Text("Continuar sin Guión")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.dismissNoScriptDialog()
                        viewModel.openTextEditor(true)
                    }
                ) {
                    Text("Escribir Guión")
                }
            }
        )
    }

    // Recording Settings Dialog
    if (uiState.showRecordingSettingsDialog) {
        RecordingSettingsDialog(
            initialBitrate = uiState.recBitrateKbps,
            initialChannels = uiState.recChannels,
            initialSampleRate = uiState.recSampleRateHz,
            initialPrefix = uiState.recNamePrefix,
            initialSuffix = uiState.processedSuffix,
            initialInputDeviceId = uiState.recInputDeviceId,
            initialInputDeviceName = uiState.recInputDeviceName,
            initialTimeWarningMode = uiState.timeWarningMode,
            initialTimeWarningCustomMins = uiState.timeWarningCustomMins,
            onOpenTimeWarnings = { viewModel.toggleTimeWarningDialog(true) },
            onOpenAbout = { viewModel.toggleAboutDialog(true) },
            onDismiss = { viewModel.toggleRecordingSettingsDialog(false) },
            onSave = { bitrate, channels, sampleRate, prefix, suffix, deviceId, deviceName ->
                viewModel.saveRecordingSettings(bitrate, channels, sampleRate, prefix, suffix, deviceId, deviceName)
            }
        )
    }

    // Time Warning Settings Dialog
    if (uiState.showTimeWarningDialog) {
        TimeWarningDialog(
            initialMode = uiState.timeWarningMode,
            initialCustomMins = uiState.timeWarningCustomMins,
            onDismiss = { viewModel.toggleTimeWarningDialog(false) },
            onSave = { mode, customMins ->
                viewModel.saveTimeWarningSettings(mode, customMins)
            }
        )
    }

    // About Dialog
    if (uiState.showAboutDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleAboutDialog(false) },
            confirmButton = {},
            text = {
                AboutScreenContent(
                    onClose = { viewModel.toggleAboutDialog(false) }
                )
            }
        )
    }

    // Auphonic Credentials Settings Dialog
    if (uiState.showCredentialsDialog) {
        AuphonicCredentialsDialog(
            initialApiKey = uiState.apiKey,
            initialSelectedPresetUuid = uiState.selectedPresetUuid,
            initialIsCustomPreset = uiState.isCustomPreset,
            initialCustomPresetInput = uiState.customPresetInput,
            initialTrimAudioEnabled = uiState.trimAudioEnabled,
            presetsList = uiState.presetsList,
            isLoadingPresets = uiState.isLoadingPresets,
            onFetchPresets = { viewModel.fetchPresetsFromApi(it) },
            onDismiss = { viewModel.toggleCredentialsDialog(false) },
            onSave = { apiKey, presetUuid, isCustom, customInput, trimAudio ->
                viewModel.saveAuphonicSettings(
                    apiKey,
                    presetUuid,
                    isCustom,
                    customInput,
                    trimAudio
                )
            }
        )
    }
}

@Composable
fun SplashScreenContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(36.dp)
        ) {
            Spacer(Modifier.height(30.dp))

            // Central Logo Hero con espaciado amplio
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 16.dp,
                    modifier = Modifier.size(120.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = RecordingRed,
                            modifier = Modifier.size(68.dp)
                        )
                    }
                }

                Text(
                    text = "Grabador Prestocast",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "🎙️ Audio de Alta Fidelidad & Teleprompter",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }

            // Pie de página con versión y fecha/hora totalmente centrados y mas espaciados
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Versión v${com.prestocast.grabador.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = com.prestocast.grabador.BuildConfig.BUILD_TIMESTAMP,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun AboutScreenContent(
    onClose: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(
                text = "Grabador Prestocast",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Grabación de Audio & Teleprompter",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Versión: v${com.prestocast.grabador.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = com.prestocast.grabador.BuildConfig.BUILD_TIMESTAMP,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (onClose != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuphonicResultScreen(
    rawFile: File,
    originalDurationMs: Long,
    targetFileName: String,
    onBack: () -> Unit,
    onApplyAndSave: (TrimMode) -> Unit
) {
    var selectedTrimMode by remember { mutableStateOf(TrimMode.TRIM_END) }
    val rawDurationMs = remember(rawFile) { AudioTrimmer.getAudioDurationMs(rawFile) }
    val exceedMs = (rawDurationMs - originalDurationMs).coerceAtLeast(0L)

    val context = LocalContext.current
    val resultPlayer = remember { AudioPlayer(context) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                delay(200)
                currentPosMs = resultPlayer.getCurrentPosition()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            resultPlayer.stop()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Ajuste de Recorte y Codificación MP3") },
                navigationIcon = {
                    IconButton(onClick = {
                        resultPlayer.stop()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            resultPlayer.stop()
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar")
                    }

                    Button(
                        onClick = {
                            resultPlayer.stop()
                            onApplyAndSave(selectedTrimMode)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AuphonicOrange)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Aplicar y Guardar MP3", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Audio Information Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Audio Recibido de Auphonic (MP3 Nativo)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Nombre final a guardar: $targetFileName",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(text = "Duración original: ${formatTime(originalDurationMs)}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Duración recibida MP3: ${formatTime(rawDurationMs)}", style = MaterialTheme.typography.bodySmall)
                    if (exceedMs > 0) {
                        Text(
                            text = "Excedente a recortar: ${formatTime(exceedMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Reproductor de Audio para escuchar el fichero recibido
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Reproducir Audio Recibido (Para verificar inicio/final)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    resultPlayer.pause()
                                    isPlaying = false
                                } else {
                                    val isNearEnd = rawDurationMs > 0 && currentPosMs >= rawDurationMs - 300
                                    if (isNearEnd) {
                                        currentPosMs = 0
                                    }
                                    isPlaying = true
                                    resultPlayer.play(rawFile) {
                                        isPlaying = false
                                        currentPosMs = rawDurationMs.toInt()
                                    }
                                    if (isNearEnd) {
                                        resultPlayer.seekTo(0)
                                    } else {
                                        resultPlayer.seekTo(currentPosMs)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Reproducir / Pausar Audio Recibido",
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        val floatPos = currentPosMs.toFloat()
                        val floatTotal = rawDurationMs.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = floatPos.coerceIn(0f, floatTotal),
                            onValueChange = {
                                currentPosMs = it.toInt()
                                resultPlayer.seekTo(currentPosMs)
                            },
                            valueRange = 0f..floatTotal,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatTime(currentPosMs.toLong()), style = MaterialTheme.typography.bodySmall)
                        Text(text = formatTime(rawDurationMs), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Trim Mode Options Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Selecciona la forma de recuadrar la duración exacta:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTrimMode == TrimMode.TRIM_END) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTrimMode == TrimMode.TRIM_END,
                            onClick = { selectedTrimMode = TrimMode.TRIM_END }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Cortar al final", fontWeight = FontWeight.Bold)
                            Text("Conserva el inicio y desecha la cola sobrante", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTrimMode == TrimMode.TRIM_START) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTrimMode == TrimMode.TRIM_START,
                            onClick = { selectedTrimMode = TrimMode.TRIM_START }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Cortar al inicio", fontWeight = FontWeight.Bold)
                            Text("Conserva el final y desecha la cabecera sobrante", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTrimMode == TrimMode.NO_TRIM) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTrimMode == TrimMode.NO_TRIM,
                            onClick = { selectedTrimMode = TrimMode.NO_TRIM }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Sin recortar", fontWeight = FontWeight.Bold)
                            Text("Mantener todo el audio recibido sin recortes", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingSettingsDialog(
    initialBitrate: Int,
    initialChannels: Int,
    initialSampleRate: Int,
    initialPrefix: String,
    initialSuffix: String,
    initialInputDeviceId: Int,
    initialInputDeviceName: String,
    initialTimeWarningMode: Int,
    initialTimeWarningCustomMins: Int,
    onOpenTimeWarnings: () -> Unit,
    onOpenAbout: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        bitrate: Int,
        channels: Int,
        sampleRate: Int,
        prefix: String,
        suffix: String,
        inputDeviceId: Int,
        inputDeviceName: String
    ) -> Unit
) {
    val context = LocalContext.current

    var bitrate by remember { mutableIntStateOf(initialBitrate) }
    var channels by remember { mutableIntStateOf(initialChannels) }
    var sampleRate by remember { mutableIntStateOf(initialSampleRate) }
    var prefix by remember { mutableStateOf(initialPrefix) }
    var suffix by remember { mutableStateOf(initialSuffix) }

    var selectedInputDeviceId by remember { mutableIntStateOf(initialInputDeviceId) }
    var selectedInputDeviceName by remember { mutableStateOf(initialInputDeviceName) }

    var availableMicrophones by remember {
        mutableStateOf(AudioInputManager.getAvailableMicrophones(context))
    }

    var bitrateExpanded by remember { mutableStateOf(false) }
    var sampleRateExpanded by remember { mutableStateOf(false) }
    var inputDeviceExpanded by remember { mutableStateOf(false) }

    val bitrateOptions = listOf(64, 96, 128, 160, 192, 256, 320)
    val sampleRateOptions = listOf(22050, 32000, 44100, 48000)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opciones de Grabación") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Configura la calidad, fuente de entrada y nomenclatura de los archivos.",
                    style = MaterialTheme.typography.bodySmall
                )

                // 1. Fuente de micrófono / Entrada de audio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fuente de Micrófono:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                    IconButton(
                        onClick = {
                            availableMicrophones = AudioInputManager.getAvailableMicrophones(context)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Actualizar fuentes de audio",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = inputDeviceExpanded,
                    onExpandedChange = { inputDeviceExpanded = !inputDeviceExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedInputDeviceName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = inputDeviceExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = inputDeviceExpanded,
                        onDismissRequest = { inputDeviceExpanded = false }
                    ) {
                        availableMicrophones.forEach { mic ->
                            val isSelected = (mic.id == selectedInputDeviceId)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = mic.name,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedInputDeviceId = mic.id
                                    selectedInputDeviceName = mic.name
                                    inputDeviceExpanded = false
                                },
                                modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 2. Configuración de Avisos de Tiempo por Auriculares
                Text("Avisos de Tiempo (Auriculares):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = { onOpenTimeWarnings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.AccessAlarm,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    val warningText = when (initialTimeWarningMode) {
                        1 -> "Aviso a 1 Minuto"
                        2 -> "Aviso a 10 Minutos"
                        3 -> "Aviso a $initialTimeWarningCustomMins Minutos"
                        else -> "Sin aviso de tiempo"
                    }
                    Text(warningText, fontWeight = FontWeight.SemiBold)
                }

                HorizontalDivider()

                // 3. Bitrate
                Text("Tasa de bits (Bitrate):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                ExposedDropdownMenuBox(
                    expanded = bitrateExpanded,
                    onExpandedChange = { bitrateExpanded = !bitrateExpanded }
                ) {
                    OutlinedTextField(
                        value = "$bitrate kbps" + if (bitrate == 128) " (Estándar)" else "",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitrateExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = bitrateExpanded,
                        onDismissRequest = { bitrateExpanded = false }
                    ) {
                        bitrateOptions.forEach { option ->
                            val isSelected = (option == bitrate)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$option kbps" + if (option == 128) " (Estándar)" else "",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    bitrate = option
                                    bitrateExpanded = false
                                },
                                modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier
                            )
                        }
                    }
                }

                // 4. Canales (Stereo vs Mono)
                Text("Canales de Audio:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = channels == 2,
                        onClick = { channels = 2 },
                        label = { Text("Stereo (2 ch)") },
                        leadingIcon = if (channels == 2) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = channels == 1,
                        onClick = { channels = 1 },
                        label = { Text("Mono (1 ch)") },
                        leadingIcon = if (channels == 1) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 5. Frecuencia de muestreo (Hercios)
                Text("Frecuencia de Muestreo (Hercios):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                ExposedDropdownMenuBox(
                    expanded = sampleRateExpanded,
                    onExpandedChange = { sampleRateExpanded = !sampleRateExpanded }
                ) {
                    val labelStr = when (sampleRate) {
                        22050 -> "22.05 kHz (22050 Hz)"
                        32000 -> "32.0 kHz (32000 Hz)"
                        44100 -> "44.1 kHz (44100 Hz - CD/Estándar)"
                        48000 -> "48.0 kHz (48000 Hz - Broadcast)"
                        else -> "$sampleRate Hz"
                    }
                    OutlinedTextField(
                        value = labelStr,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = sampleRateExpanded,
                        onDismissRequest = { sampleRateExpanded = false }
                    ) {
                        sampleRateOptions.forEach { option ->
                            val isSelected = (option == sampleRate)
                            val textOpt = when (option) {
                                22050 -> "22.05 kHz (22050 Hz)"
                                32000 -> "32.0 kHz (32000 Hz)"
                                44100 -> "44.1 kHz (44100 Hz - Estándar)"
                                48000 -> "48.0 kHz (48000 Hz - Broadcast)"
                                else -> "$option Hz"
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = textOpt,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    sampleRate = option
                                    sampleRateExpanded = false
                                },
                                modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 6. Nombre / Prefijo para grabaciones originales
                Text("Nomenclatura de Archivos:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("Prefijo para nuevas grabaciones") },
                    placeholder = { Text("Grabacion_") },
                    singleLine = true,
                    supportingText = { Text("Ejemplo: ${prefix.ifBlank { "Grabacion_" }}yyyyMMdd_HHmmss.m4a") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 7. Sufijo para archivos Auphonic
                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = { Text("Sufijo para audio procesado (Auphonic MP3)") },
                    placeholder = { Text("_procesado") },
                    singleLine = true,
                    supportingText = { Text("Ejemplo: MiAudio${suffix.ifBlank { "_procesado" }}.mp3") },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // 8. Botón Acerca de
                OutlinedButton(
                    onClick = { onOpenAbout() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Acerca de", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(bitrate, channels, sampleRate, prefix, suffix, selectedInputDeviceId, selectedInputDeviceName) }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeWarningDialog(
    initialMode: Int,
    initialCustomMins: Int,
    onDismiss: () -> Unit,
    onSave: (mode: Int, customMins: Int) -> Unit
) {
    var selectedMode by remember { mutableIntStateOf(initialMode) }
    var customMinsText by remember { mutableStateOf(initialCustomMins.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Avisos de Tiempo ⏱️") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Configura avisos sonoros que sonarán EXCLUSIVAMENTE por tus auriculares (Bluetooth o cable) antes de finalizar el tiempo fijado (-20s, -10s, -5s, -2s):",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedMode == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(6.dp)
                ) {
                    RadioButton(selected = selectedMode == 0, onClick = { selectedMode = 0 })
                    Spacer(Modifier.width(8.dp))
                    Text("Sin aviso (Desactivado)", fontWeight = FontWeight.SemiBold)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedMode == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(6.dp)
                ) {
                    RadioButton(selected = selectedMode == 1, onClick = { selectedMode = 1 })
                    Spacer(Modifier.width(8.dp))
                    Text("1 minuto", fontWeight = FontWeight.SemiBold)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedMode == 2) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(6.dp)
                ) {
                    RadioButton(selected = selectedMode == 2, onClick = { selectedMode = 2 })
                    Spacer(Modifier.width(8.dp))
                    Text("10 minutos", fontWeight = FontWeight.SemiBold)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedMode == 3) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(6.dp)
                ) {
                    RadioButton(selected = selectedMode == 3, onClick = { selectedMode = 3 })
                    Spacer(Modifier.width(8.dp))
                    Text("Personalizar minutos", fontWeight = FontWeight.SemiBold)
                }

                if (selectedMode == 3) {
                    OutlinedTextField(
                        value = customMinsText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                customMinsText = input
                            }
                        },
                        label = { Text("Minutos") },
                        singleLine = true,
                        placeholder = { Text("Ejemplo: 5") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedMins = customMinsText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                    onSave(selectedMode, parsedMins)
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    initialText: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onSaveAndRecord: (String) -> Unit
) {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        )
    }
    var showExitDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }

    fun handleBackAttempt() {
        if (textFieldValue.text != initialText) {
            showExitDialog = true
        } else {
            onBack()
        }
    }

    BackHandler {
        handleBackAttempt()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Guión / Escaleta",
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { handleBackAttempt() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(textFieldValue.text) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Guardar", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSave(textFieldValue.text) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Guardar Texto")
                    }

                    Button(
                        onClick = { onSaveAndRecord(textFieldValue.text) },
                        enabled = textFieldValue.text.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RecordingRed),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Guardar y Grabar",
                            fontSize = 13.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Introduce guión:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = {
                            val clip = clipboardManager?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val pasted = clip.getItemAt(0).coerceToText(context)?.toString()
                                if (!pasted.isNullOrEmpty()) {
                                    val currentText = textFieldValue.text
                                    val sel = textFieldValue.selection
                                    val start = sel.min.coerceIn(0, currentText.length)
                                    val end = sel.max.coerceIn(0, currentText.length)

                                    val newText = currentText.replaceRange(start, end, pasted)
                                    val newCursorPos = start + pasted.length

                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursorPos)
                                    )
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "Pegar",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    TextButton(
                        onClick = {
                            textFieldValue = TextFieldValue(
                                text = "",
                                selection = TextRange(0)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "Borrar",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("Ejemplo: 'Hola a todos, bienvenidos a este podcast...'") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp)
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Cambios sin guardar") },
            text = {
                Text(
                    "Has modificado el guión. ¿Deseas guardar los cambios realizados antes de salir?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        onSave(textFieldValue.text)
                    }
                ) {
                    Text("Guardar y Salir")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            onBack()
                        }
                    ) {
                        Text("Descartar", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("Cancelar")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuphonicCredentialsDialog(
    initialApiKey: String,
    initialSelectedPresetUuid: String,
    initialIsCustomPreset: Boolean,
    initialCustomPresetInput: String,
    initialTrimAudioEnabled: Boolean,
    presetsList: List<AuphonicPreset>,
    isLoadingPresets: Boolean,
    onFetchPresets: (apiKey: String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        apiKey: String,
        selectedPresetUuid: String,
        isCustomPreset: Boolean,
        customPresetInput: String,
        trimAudioEnabled: Boolean
    ) -> Unit
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var selectedPresetUuid by remember { mutableStateOf(initialSelectedPresetUuid) }
    var isCustomPreset by remember { mutableStateOf(initialIsCustomPreset) }
    var customPresetInput by remember { mutableStateOf(initialCustomPresetInput) }
    var trimAudioEnabled by remember { mutableStateOf(initialTrimAudioEnabled) }

    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (initialApiKey.isNotBlank()) {
            onFetchPresets(initialApiKey)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuración de Auphonic") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Configura tus credenciales y preferencias de postprocesamiento con Auphonic.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key / Bearer Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Presets Header + Refresh Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Preset de Auphonic:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(
                        onClick = { if (apiKey.isNotBlank()) onFetchPresets(apiKey) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Actualizar presets",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                val selectedLabel = when {
                    isCustomPreset -> "Personalizado (introducir manualmente)"
                    selectedPresetUuid.isNotBlank() -> {
                        SYSTEM_PRESETS.find { it.uuid == selectedPresetUuid }?.name
                            ?: presetsList.find { it.uuid == selectedPresetUuid }?.name
                            ?: selectedPresetUuid
                    }
                    else -> "Auphonic Cleaner (auphonic_cleaner)"
                }

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("--- PRESETS DEL SISTEMA ---", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) },
                            onClick = {},
                            enabled = false
                        )

                        SYSTEM_PRESETS.forEach { preset ->
                            val isSelected = (!isCustomPreset && selectedPresetUuid == preset.uuid)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = preset.name,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedPresetUuid = preset.uuid
                                    isCustomPreset = false
                                    dropdownExpanded = false
                                },
                                modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier
                            )
                        }

                        if (presetsList.isNotEmpty()) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("--- MIS PRESETS (AUPHONIC.COM) ---", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) },
                                onClick = {},
                                enabled = false
                            )

                            presetsList.forEach { preset ->
                                val isSelected = (!isCustomPreset && selectedPresetUuid == preset.uuid)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${preset.name} (${preset.uuid.take(8)}...)",
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedPresetUuid = preset.uuid
                                        isCustomPreset = false
                                        dropdownExpanded = false
                                    },
                                    modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier
                                )
                            }
                        }

                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Personalizado (introducir manualmente)",
                                        fontWeight = if (isCustomPreset) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCustomPreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isCustomPreset) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                isCustomPreset = true
                                dropdownExpanded = false
                            },
                            modifier = if (isCustomPreset) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier
                        )
                    }
                }

                if (isLoadingPresets) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Actualizando presets de Auphonic...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (isCustomPreset) {
                    OutlinedTextField(
                        value = customPresetInput,
                        onValueChange = { customPresetInput = it },
                        label = { Text("Preset Personalizado (ID o nombre)") },
                        singleLine = true,
                        placeholder = { Text("auphonic_cleaner") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                // Checkbox Recorte de Audio
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = trimAudioEnabled,
                        onCheckedChange = { trimAudioEnabled = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Recortar audio recibido a la duración exacta del original",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        apiKey,
                        selectedPresetUuid,
                        isCustomPreset,
                        customPresetInput,
                        trimAudioEnabled
                    )
                },
                enabled = apiKey.isNotBlank()
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private fun getCleanDisplayFileName(file: File?, isProcessed: Boolean, suffix: String): String {
    if (file == null) return ""
    val ext = file.extension.ifBlank { if (isProcessed) "mp3" else "m4a" }
    val baseName = file.nameWithoutExtension
        .removePrefix("Importado_")
        .removePrefix("Auphonic_")
        .removePrefix("auphonic_raw_")

    val cleanSuffix = suffix.ifBlank { "_procesado" }
    return if (isProcessed) {
        "${baseName}${cleanSuffix}.mp3"
    } else {
        "${baseName}.${ext}"
    }
}

private fun sendSystemMediaPlayPause(context: Context) {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun shareAudioFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Compartir audio con...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error al compartir archivo: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
