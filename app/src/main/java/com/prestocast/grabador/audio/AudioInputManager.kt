package com.prestocast.grabador.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val type: Int
)

object AudioInputManager {

    fun getAvailableMicrophones(context: Context): List<AudioInputDevice> {
        val list = mutableListOf<AudioInputDevice>()

        // Opción por defecto
        list.add(
            AudioInputDevice(
                id = -1,
                name = "Micrófonos del teléfono (Por defecto)",
                type = -1
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                for (dev in devices) {
                    val productName = dev.productName?.toString()?.trim() ?: ""
                    val typeName = getDeviceTypeName(dev.type)
                    val displayName = if (productName.isNotBlank() && !productName.equals("microphone", ignoreCase = true)) {
                        "$productName ($typeName)"
                    } else {
                        typeName
                    }

                    if (dev.id != -1) {
                        list.add(AudioInputDevice(id = dev.id, name = displayName, type = dev.type))
                    }
                }
            }
        }
        return list.distinctBy { it.id }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono integrado"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares con micrófono"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Micrófono Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Micrófono USB"
            else -> "Entrada de audio externa"
        }
    }
}
