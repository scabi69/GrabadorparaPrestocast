package com.prestocast.grabador.data

import android.content.Context
import android.content.SharedPreferences

class AuphonicPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auphonic_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getSelectedPresetUuid(): String {
        return prefs.getString(KEY_SELECTED_PRESET_UUID, "") ?: ""
    }

    fun saveSelectedPresetUuid(uuid: String) {
        prefs.edit().putString(KEY_SELECTED_PRESET_UUID, uuid.trim()).apply()
    }

    fun getIsCustomPreset(): Boolean {
        return prefs.getBoolean(KEY_IS_CUSTOM_PRESET, false)
    }

    fun saveIsCustomPreset(isCustom: Boolean) {
        prefs.edit().putBoolean(KEY_IS_CUSTOM_PRESET, isCustom).apply()
    }

    fun getCustomPresetInput(): String {
        return prefs.getString(KEY_CUSTOM_PRESET_INPUT, "") ?: ""
    }

    fun saveCustomPresetInput(input: String) {
        prefs.edit().putString(KEY_CUSTOM_PRESET_INPUT, input.trim()).apply()
    }

    fun getTrimAudioEnabled(): Boolean {
        return prefs.getBoolean(KEY_TRIM_AUDIO_ENABLED, true)
    }

    fun saveTrimAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIM_AUDIO_ENABLED, enabled).apply()
    }

    fun getSavedPresetsJson(): String {
        return prefs.getString(KEY_SAVED_PRESETS_JSON, "[]") ?: "[]"
    }

    fun saveSavedPresetsJson(json: String) {
        prefs.edit().putString(KEY_SAVED_PRESETS_JSON, json).apply()
    }

    fun getRecordingBitrateKbps(): Int {
        return prefs.getInt(KEY_REC_BITRATE, 128)
    }

    fun saveRecordingBitrateKbps(bitrate: Int) {
        prefs.edit().putInt(KEY_REC_BITRATE, bitrate).apply()
    }

    fun getRecordingChannels(): Int {
        return prefs.getInt(KEY_REC_CHANNELS, 2)
    }

    fun saveRecordingChannels(channels: Int) {
        prefs.edit().putInt(KEY_REC_CHANNELS, channels).apply()
    }

    fun getRecordingSampleRateHz(): Int {
        return prefs.getInt(KEY_REC_SAMPLE_RATE, 44100)
    }

    fun saveRecordingSampleRateHz(sampleRate: Int) {
        prefs.edit().putInt(KEY_REC_SAMPLE_RATE, sampleRate).apply()
    }

    fun getRecordingNamePrefix(): String {
        return prefs.getString(KEY_REC_PREFIX, "Grabacion_") ?: "Grabacion_"
    }

    fun saveRecordingNamePrefix(prefix: String) {
        prefs.edit().putString(KEY_REC_PREFIX, prefix.trim()).apply()
    }

    fun getProcessedSuffix(): String {
        return prefs.getString(KEY_PROCESSED_SUFFIX, "_procesado") ?: "_procesado"
    }

    fun saveProcessedSuffix(suffix: String) {
        prefs.edit().putString(KEY_PROCESSED_SUFFIX, suffix.trim()).apply()
    }

    fun getRecordingInputDeviceId(): Int {
        return prefs.getInt(KEY_REC_INPUT_DEVICE_ID, -1)
    }

    fun saveRecordingInputDeviceId(deviceId: Int) {
        prefs.edit().putInt(KEY_REC_INPUT_DEVICE_ID, deviceId).apply()
    }

    fun getRecordingInputDeviceName(): String {
        return prefs.getString(KEY_REC_INPUT_DEVICE_NAME, "Micrófonos del teléfono") ?: "Micrófonos del teléfono"
    }

    fun saveRecordingInputDeviceName(deviceName: String) {
        prefs.edit().putString(KEY_REC_INPUT_DEVICE_NAME, deviceName.trim()).apply()
    }

    fun getTeleprompterText(): String {
        return prefs.getString(KEY_TELEPROMPTER_TEXT, "") ?: ""
    }

    fun saveTeleprompterText(text: String) {
        prefs.edit().putString(KEY_TELEPROMPTER_TEXT, text).apply()
    }

    fun getAutoSaveOriginalToDownloads(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SAVE_ORIGINAL, true)
    }

    fun saveAutoSaveOriginalToDownloads(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SAVE_ORIGINAL, enabled).apply()
    }

    fun getTimeWarningMode(): Int {
        return prefs.getInt(KEY_TIME_WARNING_MODE, 0)
    }

    fun saveTimeWarningMode(mode: Int) {
        prefs.edit().putInt(KEY_TIME_WARNING_MODE, mode).apply()
    }

    fun getTimeWarningCustomMinutes(): Int {
        return prefs.getInt(KEY_TIME_WARNING_CUSTOM_MINS, 5)
    }

    fun saveTimeWarningCustomMinutes(mins: Int) {
        prefs.edit().putInt(KEY_TIME_WARNING_CUSTOM_MINS, mins).apply()
    }

    fun getAutoScrollEnabled(): Boolean {
        return prefs.getBoolean(KEY_TELEPROMPTER_AUTO_SCROLL_ENABLED, false)
    }

    fun saveAutoScrollEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEPROMPTER_AUTO_SCROLL_ENABLED, enabled).apply()
    }

    fun getAutoScrollSpeed(): Float {
        return prefs.getFloat(KEY_TELEPROMPTER_AUTO_SCROLL_SPEED, 5f)
    }

    fun saveAutoScrollSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_TELEPROMPTER_AUTO_SCROLL_SPEED, speed).apply()
    }

    fun getEffectivePreset(): String {
        if (getIsCustomPreset() && getCustomPresetInput().isNotBlank()) {
            return getCustomPresetInput()
        }
        val uuid = getSelectedPresetUuid()
        if (uuid.isNotBlank()) {
            return uuid
        }
        return DEFAULT_PRESET
    }

    fun isConfigured(): Boolean {
        return getApiKey().isNotBlank()
    }

    companion object {
        private const val KEY_API_KEY = "auphonic_api_key"
        private const val KEY_SELECTED_PRESET_UUID = "auphonic_selected_preset_uuid"
        private const val KEY_IS_CUSTOM_PRESET = "auphonic_is_custom_preset"
        private const val KEY_CUSTOM_PRESET_INPUT = "auphonic_custom_preset_input"
        private const val KEY_TRIM_AUDIO_ENABLED = "auphonic_trim_audio_enabled"
        private const val KEY_SAVED_PRESETS_JSON = "auphonic_saved_presets_json"
        private const val KEY_REC_BITRATE = "rec_bitrate_kbps"
        private const val KEY_REC_CHANNELS = "rec_channels"
        private const val KEY_REC_SAMPLE_RATE = "rec_sample_rate_hz"
        private const val KEY_REC_PREFIX = "rec_name_prefix"
        private const val KEY_PROCESSED_SUFFIX = "processed_file_suffix"
        private const val KEY_REC_INPUT_DEVICE_ID = "rec_input_device_id"
        private const val KEY_REC_INPUT_DEVICE_NAME = "rec_input_device_name"
        private const val KEY_TELEPROMPTER_TEXT = "teleprompter_text"
        private const val KEY_AUTO_SAVE_ORIGINAL = "auto_save_original_downloads"
        private const val KEY_TIME_WARNING_MODE = "time_warning_mode"
        private const val KEY_TIME_WARNING_CUSTOM_MINS = "time_warning_custom_mins"
        private const val KEY_TELEPROMPTER_AUTO_SCROLL_ENABLED = "teleprompter_auto_scroll_enabled"
        private const val KEY_TELEPROMPTER_AUTO_SCROLL_SPEED = "teleprompter_auto_scroll_speed"
        const val DEFAULT_PRESET = "auphonic_cleaner"
    }
}
