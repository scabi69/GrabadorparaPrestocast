package com.prestocast.grabador.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

object TimeWarningManager {

    fun isHeadsetConnected(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> return true
                }
            }
            return false
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isWiredHeadsetOn || audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
        }
    }

    suspend fun playBeep(freqHz: Int, durationMs: Int = 180) = withContext(Dispatchers.IO) {
        try {
            val sampleRate = 22050
            val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
            val sample = FloatArray(numSamples)
            val generatedSnd = ByteArray(2 * numSamples)

            for (i in 0 until numSamples) {
                sample[i] = sin(2 * Math.PI * i / (sampleRate.toDouble() / freqHz)).toFloat()
            }

            var idx = 0
            for (dVal in sample) {
                val shortVal = (dVal * 32767).toInt().coerceIn(-32768, 32767).toShort()
                generatedSnd[idx++] = (shortVal.toInt() and 0x00ff).toByte()
                generatedSnd[idx++] = ((shortVal.toInt() and 0xff00) ushr 8).toByte()
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(generatedSnd.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(generatedSnd, 0, generatedSnd.size)
            track.play()
            delay(durationMs.toLong() + 50)
            track.stop()
            track.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
