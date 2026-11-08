package dev.kamol1dn.syllable.spotify

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spotify sign-in in a plain WebView. Finishes itself once the web player is signed in and its
 * access token has been captured (see [SpotifyAuth]).
 */
class SpotifyLoginActivity : ComponentActivity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val status = TextView(this).apply {
            text = "Sign in with your Spotify account"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(16), dp(4))
            addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val hint = TextView(this).apply {
            text = "Google, Apple and Facebook sign-in may not work here. Use your email or username, " +
                "or ask Spotify to email you a 6-digit code."
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        web = WebView(this)
        SpotifyAuth.configure(web)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(root)

        if (savedInstanceState == null) {
            web.loadUrl(if (SpotifyAuth.isSignedIn()) SpotifyAuth.PLAYER_URL else SpotifyAuth.LOGIN_URL)
        } else {
            web.restoreState(savedInstanceState)
        }

        lifecycleScope.launch {
            SpotifyAuth.state.collect { s ->
                when {
                    s.signedIn && s.hasToken -> {
                        status.text = "Signed in ✓"
                        delay(800)
                        finish()
                    }
                    s.signedIn -> status.text = "Signed in, getting lyrics access…"
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Back navigates inside the WebView first")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
