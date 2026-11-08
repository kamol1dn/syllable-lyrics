package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.media.AudioOutput
import dev.kamol1dn.syllable.media.OutputKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTimingTest {
    private val buds = AudioOutput("bt:AA:BB", "Galaxy Buds", OutputKind.BLUETOOTH)
    private val speaker = AudioOutput("bt:CC:DD", "JBL Flip", OutputKind.BLUETOOTH)

    @Test
    fun newDevicesUseTheDefaultForTheirKind() {
        val s = SettingsState(bluetoothDefaultMs = -250)
        assertEquals(-250L, s.offsetFor(buds))
        assertEquals(0L, s.offsetFor(AudioOutput.Phone))
        assertEquals(0L, s.offsetFor(AudioOutput("wired", "Wired headphones", OutputKind.WIRED)))
        assertEquals(0L, s.offsetFor(AudioOutput.remote("com.spotify.music", "Spotify")))
    }

    @Test
    fun savedTimingIsPerDevice() {
        val s = SettingsState(
            bluetoothDefaultMs = -200,
            deviceTimings = mapOf(buds.key to DeviceTiming(buds.name, buds.kind, -400)),
        )
        assertEquals(-400L, s.offsetFor(buds))
        // Another Bluetooth device still starts from the default.
        assertEquals(-200L, s.offsetFor(speaker))
    }
}
