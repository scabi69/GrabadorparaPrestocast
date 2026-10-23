package com.prestocast.grabador.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false
    private var isPaused = false

    fun startRecording(
        outputFile: File,
        bitRateKbps: Int = 128,
        channels: Int = 2,
        sampleRateHz: Int = 44100,
        inputDeviceId: Int = -1
    ): Boolean {
        return try {
            stopRecording() // Asegurar limpieza previa

            currentOutputFile = outputFile
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            var audioSource = MediaRecorder.AudioSource.MIC

            if (audioManager != null && inputDeviceId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val targetDevice = devices.find { it.id == inputDeviceId }

                if (targetDevice != null) {
                    val isBluetooth = targetDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                      targetDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

                    if (isBluetooth) {
                        audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val commDevices = audioManager.availableCommunicationDevices
                            val btCommDevice = commDevices.find { it.id == inputDeviceId }
                                ?: commDevices.find {
                                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                                }
                            if (btCommDevice != null) {
                                audioManager.setCommunicationDevice(btCommDevice)
                            }
                        } else {
                            try {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                @Suppress("DEPRECATION")
                                audioManager.startBluetoothSco()
                                @Suppress("DEPRECATION")
                                audioManager.isBluetoothScoOn = true
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(bitRateKbps * 1000)
                setAudioSamplingRate(sampleRateHz)
                setAudioChannels(channels)

                if (inputDeviceId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && audioManager != null) {
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val targetDevice = devices?.find { it.id == inputDeviceId }
                    if (targetDevice != null) {
                        try {
                            setPreferredDevice(targetDevice)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            isPaused = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            false
        }
    }

    fun pauseRecording(): Boolean {
        if (!isRecording || isPaused) return false
        return try {
            mediaRecorder?.pause()
            isPaused = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun resumeRecording(): Boolean {
        if (!isRecording || !isPaused) return false
        return try {
            mediaRecorder?.resume()
            isPaused = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return currentOutputFile
        return try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                    // Si la grabación fue muy corta
                }
                release()
            }
            mediaRecorder = null
            isRecording = false
            isPaused = false
            cleanupBluetoothSco()
            currentOutputFile
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            null
        }
    }

    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun isRecording(): Boolean = isRecording
    fun isPaused(): Boolean = isPaused

    private fun cleanupBluetoothSco() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanup() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignorar
        }
        cleanupBluetoothSco()
        mediaRecorder = null
        isRecording = false
        isPaused = false
    }
}
