package dev.kamol1dn.syllable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.kamol1dn.syllable.ui.ListenScreen
import dev.kamol1dn.syllable.ui.MainScreen
import dev.kamol1dn.syllable.ui.SettingsScreen
import dev.kamol1dn.syllable.spotify.SpotifyLoginActivity
import dev.kamol1dn.syllable.ui.SyllableTheme

enum class Screen { MAIN, LISTEN, SETTINGS }

/** Settings slides in from the side; Listen zooms in like a focused mode. Going back reverses it. */
private fun screenTransition(from: Screen, to: Screen): ContentTransform {
    val forward = to != Screen.MAIN
    val other = if (forward) to else from
    val ease = tween<Float>(380, easing = FastOutSlowInEasing)
    val slide = tween<IntOffset>(380, easing = FastOutSlowInEasing)
    return when (other) {
        Screen.SETTINGS -> if (forward) {
            (slideInHorizontally(slide) { it / 4 } + fadeIn(ease)) togetherWith (scaleOut(ease, targetScale = 0.96f) + fadeOut(ease))
        } else {
            (scaleIn(ease, initialScale = 0.96f) + fadeIn(ease)) togetherWith (slideOutHorizontally(slide) { it / 4 } + fadeOut(ease))
        }
        else -> if (forward) {
            (scaleIn(ease, initialScale = 0.9f) + fadeIn(ease)) togetherWith (scaleOut(ease, targetScale = 1.06f) + fadeOut(ease))
        } else {
            (scaleIn(ease, initialScale = 1.06f) + fadeIn(ease)) togetherWith (scaleOut(ease, targetScale = 0.9f) + fadeOut(ease))
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the bar icons stay light even when the phone uses a light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            SyllableTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                // Counts visits to the listen screen, so each visit starts a fresh listen.
                var listenEntry by rememberSaveable { mutableIntStateOf(0) }
                val mode by vm.mode.collectAsStateWithLifecycle()
                BackHandler(enabled = screen != Screen.MAIN) {
                    screen = Screen.MAIN
                }
                // Back on the lyrics of a song found with the mic returns to what the phone is playing.
                BackHandler(enabled = screen == Screen.MAIN && mode == Mode.RECOGNIZED) {
                    vm.clearRecognized()
                }
                val spotifyLogin = { startActivity(Intent(this@MainActivity, SpotifyLoginActivity::class.java)) }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { screenTransition(initialState, targetState) },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.MAIN -> MainScreen(
                            vm,
                            onListen = { vm.resetRecognition(); listenEntry++; screen = Screen.LISTEN },
                            onSettings = { screen = Screen.SETTINGS },
                            onSpotifyLogin = spotifyLogin,
                        )
                        // The result stays while the screen fades out; it's cleared on the next visit.
                        Screen.LISTEN -> ListenScreen(
                            vm,
                            onBack = { screen = Screen.MAIN },
                            onShowLyrics = { screen = Screen.MAIN },
                            entry = listenEntry,
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            vm,
                            onBack = { screen = Screen.MAIN },
                            onSpotifyLogin = spotifyLogin,
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

    init {
        // Some route changes (like picking the phone speaker in the output switcher) send no event,
        // so check the output every few seconds while Syllable is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(3_000)
                    vm.refreshOutput()
                }
            }
        }
    }
}
