package com.cs22study.seethere.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSettings(
    val screenshotCornerLabelEnabled: Boolean = true,
    val allowLocalLanHttp: Boolean = false,
    val trafficSaverMode: Boolean = false,
    val searchRadiusMeters: Int = 3_000,
    val selectedAnchorId: String = "anchor_ntu",
)

class PreferencesRepository(context: Context) {

    private val prefs = context.getSharedPreferences("seethere_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadFromPrefs(): UserSettings = UserSettings(
        screenshotCornerLabelEnabled = prefs.getBoolean(KEY_CORNER_LABEL, true),
        allowLocalLanHttp = prefs.getBoolean(KEY_ALLOW_LAN_HTTP, false),
        trafficSaverMode = prefs.getBoolean(KEY_TRAFFIC_SAVER, false),
        searchRadiusMeters = prefs.getInt(KEY_RADIUS_METERS, 3_000),
        selectedAnchorId = prefs.getString(KEY_ANCHOR_ID, "anchor_ntu") ?: "anchor_ntu",
    )

    fun setScreenshotCornerLabelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CORNER_LABEL, enabled).apply()
        _settings.value = _settings.value.copy(screenshotCornerLabelEnabled = enabled)
    }

    fun setAllowLocalLanHttp(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_LAN_HTTP, enabled).apply()
        _settings.value = _settings.value.copy(allowLocalLanHttp = enabled)
    }

    fun setTrafficSaverMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRAFFIC_SAVER, enabled).apply()
        _settings.value = _settings.value.copy(trafficSaverMode = enabled)
    }

    fun setSearchRadiusMeters(radiusMeters: Int) {
        prefs.edit().putInt(KEY_RADIUS_METERS, radiusMeters).apply()
        _settings.value = _settings.value.copy(searchRadiusMeters = radiusMeters)
    }

    fun setSelectedAnchorId(anchorId: String) {
        prefs.edit().putString(KEY_ANCHOR_ID, anchorId).apply()
        _settings.value = _settings.value.copy(selectedAnchorId = anchorId)
    }

    companion object {
        private const val KEY_CORNER_LABEL = "screenshot_corner_label_enabled"
        private const val KEY_ALLOW_LAN_HTTP = "allow_local_lan_http"
        private const val KEY_TRAFFIC_SAVER = "traffic_saver_mode"
        private const val KEY_RADIUS_METERS = "search_radius_meters"
        private const val KEY_ANCHOR_ID = "selected_anchor_id"
    }
}
