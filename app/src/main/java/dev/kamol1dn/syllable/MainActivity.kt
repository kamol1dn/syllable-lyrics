package dev.kamol1dn.syllable

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kamol1dn.syllable.ui.ListenScreen
import dev.kamol1dn.syllable.ui.MainScreen
import dev.kamol1dn.syllable.ui.SettingsScreen
import dev.kamol1dn.syllable.spotify.SpotifyLoginActivity
import dev.kamol1dn.syllable.ui.SyllableTheme

enum class Screen { MAIN, LISTEN, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyllableTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                val settings by vm.settings.state.collectAsStateWithLifecycle()
                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                BackHandler(enabled = screen != Screen.MAIN) {
                    screen = Screen.MAIN
                }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.MAIN -> MainScreen(
                            vm,
                            onListen = { screen = Screen.LISTEN },
                            onSettings = { screen = Screen.SETTINGS },
                        )
                        Screen.LISTEN -> ListenScreen(
                            vm,
                            onBack = { vm.resetRecognition(); screen = Screen.MAIN },
                            onShowLyrics = { screen = Screen.MAIN },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            vm,
                            onBack = { screen = Screen.MAIN },
                            onSpotifyLogin = {
                                startActivity(Intent(this@MainActivity, SpotifyLoginActivity::class.java))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
