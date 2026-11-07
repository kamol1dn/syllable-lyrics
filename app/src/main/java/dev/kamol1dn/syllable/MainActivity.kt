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
                    transitionSpec = { screenTransition(initialState, targetState) },
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
