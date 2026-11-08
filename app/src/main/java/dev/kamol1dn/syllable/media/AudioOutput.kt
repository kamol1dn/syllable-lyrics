package dev.kamol1dn.syllable.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutputKind { PHONE, WIRED, USB, BLUETOOTH, REMOTE }

/** Where the sound is going. [key] stays the same for one device, so its timing can be saved. */
data class AudioOutput(val key: String, val name: String, val kind: OutputKind) {
    companion object {
        val Phone = AudioOutput("phone", "Phone speaker", OutputKind.PHONE)

        /** The player streams to another device (Cast, Spotify Connect), so the phone's audio route doesn't matter. */
        fun remote(packageName: String, appName: String) =
            AudioOutput("remote:$packageName", "Casting from $appName", OutputKind.REMOTE)
    }
}

/** Follows the device media audio is routed to: phone speaker, wired, USB or Bluetooth. */
class AudioOutputTracker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _current = MutableStateFlow(compute())
    val current: StateFlow<AudioOutput> = _current.asStateFlow()

    init {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, handler)
    }

    fun refresh() {
        val next = compute()
        if (next != _current.value) _current.value = next
    }

    private fun compute(): AudioOutput {
        // Android 13+ says exactly where media goes, which also covers picking the phone speaker
        // in the output switcher while headphones stay connected.
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audio.getAudioDevicesForAttributes(MEDIA) }.getOrNull()
                ?.firstNotNullOfOrNull(::describe)
                ?.let { return it }
        }
        // Older versions: media follows the most recently connected headset, so prefer those over the speaker.
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull(::describe)
            .maxByOrNull { rank(it.kind) }
            ?: AudioOutput.Phone
    }

    private fun rank(kind: OutputKind) = when (kind) {
        OutputKind.BLUETOOTH -> 4
        OutputKind.USB -> 3
        OutputKind.WIRED -> 2
        OutputKind.PHONE -> 1
        OutputKind.REMOTE -> 0
    }

    private fun describe(d: AudioDeviceInfo): AudioOutput? {
        val product = d.productName?.toString()?.trim().orEmpty()
        val address = if (Build.VERSION.SDK_INT >= 28) d.address.orEmpty() else ""
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutput.Phone
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                AudioOutput("wired", "Wired headphones", OutputKind.WIRED)
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK ->
                AudioOutput("line", "Aux cable", OutputKind.WIRED)
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, TYPE_HDMI_EARC ->
                AudioOutput("hdmi", "HDMI", OutputKind.WIRED)
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                AudioOutput("usb:$product", product.ifEmpty { "USB audio" }, OutputKind.USB)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID,
            TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST ->
                AudioOutput("bt:${address.ifEmpty { product }}", product.ifEmpty { "Bluetooth device" }, OutputKind.BLUETOOTH)
            else -> null
        }
    }

    private companion object {
        val MEDIA: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // Newer device types, spelled out so the code compiles against any API level.
        const val TYPE_HDMI_EARC = 29
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
        const val TYPE_BLE_BROADCAST = 30
    }
}
