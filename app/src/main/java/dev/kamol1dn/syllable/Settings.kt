package dev.kamol1dn.syllable

import android.content.Context
import dev.kamol1dn.syllable.media.AudioOutput
import dev.kamol1dn.syllable.media.OutputKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Saved timing for one output device. Positive shows lyrics earlier. */
data class DeviceTiming(val name: String, val kind: OutputKind, val offsetMs: Long)

data class SettingsState(
    val useSpicy: Boolean = true,
    /** Your own Spicy Lyrics key. Empty means the key built into the app, if any. */
    val spicyKey: String = "",
    val useSpotify: Boolean = true,
    val useMusixmatch: Boolean = true,
    val useNetEase: Boolean = true,
    /**
     * Starting timing for a Bluetooth device you haven't adjusted yet. Bluetooth audio reaches your
     * ears a little after the player reports it, so lyrics wait a bit by default.
     */
    val bluetoothDefaultMs: Long = DEFAULT_BLUETOOTH_MS,
    /** Timing per output device, keyed by [AudioOutput.key]. */
    val deviceTimings: Map<String, DeviceTiming> = emptyMap(),
    val textScale: Float = 1f,
    val blurInactive: Boolean = true,
    val keepScreenOn: Boolean = true,
) {
    fun defaultOffset(kind: OutputKind): Long = if (kind == OutputKind.BLUETOOTH) bluetoothDefaultMs else 0L

    /** The offset to use for [output]: what you saved for it, or the default for its kind. */
    fun offsetFor(output: AudioOutput): Long = deviceTimings[output.key]?.offsetMs ?: defaultOffset(output.kind)

    companion object {
        const val DEFAULT_BLUETOOTH_MS = -200L
        const val MAX_OFFSET_MS = 3_000L
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val useSpicy get() = _state.value.useSpicy
    val spicyKey: String?
        get() = _state.value.spicyKey.trim().ifEmpty { BuildConfig.SPICY_LYRICS_KEY }.ifEmpty { null }
    val useSpotify get() = _state.value.useSpotify
    val useMusixmatch get() = _state.value.useMusixmatch
    val useNetEase get() = _state.value.useNetEase

    private fun read() = SettingsState(
        useSpicy = prefs.getBoolean("spicy", true),
        spicyKey = prefs.getString("spicy_key", "").orEmpty(),
        useSpotify = prefs.getBoolean("spotify", true),
        useMusixmatch = prefs.getBoolean("musixmatch", true),
        useNetEase = prefs.getBoolean("netease", true),
        bluetoothDefaultMs = prefs.getLong("bt_default", SettingsState.DEFAULT_BLUETOOTH_MS),
        deviceTimings = readTimings(prefs.getString("device_timings", null)),
        textScale = prefs.getFloat("scale", 1f),
        blurInactive = prefs.getBoolean("blur", true),
        keepScreenOn = prefs.getBoolean("screen_on", true),
    )

    fun update(block: (SettingsState) -> SettingsState) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putBoolean("spicy", s.useSpicy)
            .putString("spicy_key", s.spicyKey)
            .putBoolean("spotify", s.useSpotify)
            .putBoolean("musixmatch", s.useMusixmatch)
            .putBoolean("netease", s.useNetEase)
            .putLong("bt_default", s.bluetoothDefaultMs)
            .putString("device_timings", writeTimings(s.deviceTimings))
            .remove("offset") // the old single offset, replaced by timing per device
            .putFloat("scale", s.textScale)
            .putBoolean("blur", s.blurInactive)
            .putBoolean("screen_on", s.keepScreenOn)
            .apply()
    }

    /** Saves the timing for [output], so it comes back whenever you play through it again. */
    fun setTiming(output: AudioOutput, offsetMs: Long) = update {
        val clamped = offsetMs.coerceIn(-SettingsState.MAX_OFFSET_MS, SettingsState.MAX_OFFSET_MS)
        it.copy(deviceTimings = it.deviceTimings + (output.key to DeviceTiming(output.name, output.kind, clamped)))
    }

    /** Forgets the saved timing for a device, so it goes back to the default. */
    fun forgetTiming(key: String) = update { it.copy(deviceTimings = it.deviceTimings - key) }

    private fun readTimings(json: String?): Map<String, DeviceTiming> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { k ->
            val d = o.getJSONObject(k)
            DeviceTiming(
                d.getString("name"),
                runCatching { OutputKind.valueOf(d.getString("kind")) }.getOrDefault(OutputKind.PHONE),
                d.getLong("ms"),
            )
        }
    }.getOrDefault(emptyMap())

    private fun writeTimings(m: Map<String, DeviceTiming>): String = JSONObject().apply {
        m.forEach { (k, d) -> put(k, JSONObject().put("name", d.name).put("kind", d.kind.name).put("ms", d.offsetMs)) }
    }.toString()
}
