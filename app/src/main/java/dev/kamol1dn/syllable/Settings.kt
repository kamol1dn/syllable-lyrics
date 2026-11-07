package dev.kamol1dn.syllable

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val useSpotify: Boolean = true,
    val useMusixmatch: Boolean = true,
    val useNetEase: Boolean = true,
    /** Added to the playback position; positive shows lyrics earlier. */
    val syncOffsetMs: Long = 0,
    val textScale: Float = 1f,
    val blurInactive: Boolean = true,
    val keepScreenOn: Boolean = true,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val useSpotify get() = _state.value.useSpotify
    val useMusixmatch get() = _state.value.useMusixmatch
    val useNetEase get() = _state.value.useNetEase

    private fun read() = SettingsState(
        useSpotify = prefs.getBoolean("spotify", true),
        useMusixmatch = prefs.getBoolean("musixmatch", true),
        useNetEase = prefs.getBoolean("netease", true),
        syncOffsetMs = prefs.getLong("offset", 0),
        textScale = prefs.getFloat("scale", 1f),
        blurInactive = prefs.getBoolean("blur", true),
        keepScreenOn = prefs.getBoolean("screen_on", true),
    )

    fun update(block: (SettingsState) -> SettingsState) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putBoolean("spotify", s.useSpotify)
            .putBoolean("musixmatch", s.useMusixmatch)
            .putBoolean("netease", s.useNetEase)
            .putLong("offset", s.syncOffsetMs)
            .putFloat("scale", s.textScale)
            .putBoolean("blur", s.blurInactive)
            .putBoolean("screen_on", s.keepScreenOn)
            .apply()
    }
}
