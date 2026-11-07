package dev.kamol1dn.syllable.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.kamol1dn.syllable.lyrics.Net
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spotify sign-in for lyrics. The user signs in to the Spotify web player inside a WebView; the
 * web player's own access token is picked up from its API requests. The sign-in cookie stays in the
 * WebView cookie store, so a fresh token can be fetched later by loading the web player headlessly.
 */
object SpotifyAuth {
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    const val PLAYER_URL = "https://open.spotify.com/"

    private const val PREFS = "spotify"
    private const val TOKEN_TTL_MS = 50 * 60_000L

    data class State(val signedIn: Boolean, val hasToken: Boolean, val displayName: String? = null)

    private val _state = MutableStateFlow(State(false, false))
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    @Volatile private var token: String? = null
    @Volatile private var tokenTime = 0L
    private var pendingToken: CompletableDeferred<String>? = null
    private val refreshLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = p.getString("token", null)
        tokenTime = p.getLong("token_time", 0)
        refreshState()
    }

    fun isSignedIn(): Boolean {
        val cookies = runCatching { CookieManager.getInstance().getCookie(PLAYER_URL) }.getOrNull().orEmpty()
        return cookies.contains("sp_dc=")
    }

    private fun refreshState() {
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = State(
            signedIn = runCatching { isSignedIn() }.getOrDefault(false),
            hasToken = token != null && System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS,
            displayName = p.getString("name", null),
        )
    }

    /** Called from WebViews whenever the web player sends an authorised request. */
    fun onTokenSeen(raw: String) {
        val t = raw.removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (t.length < 40 || t == token) {
            pendingToken?.complete(t)
            return
        }
        token = t
        tokenTime = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", t).putLong("token_time", tokenTime).apply()
        pendingToken?.complete(t)
        refreshState()
    }

    fun setDisplayName(name: String?) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name).apply()
        refreshState()
    }

    fun signOut() {
        token = null
        tokenTime = 0
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies { refreshState() }
        CookieManager.getInstance().flush()
        refreshState()
    }

    fun invalidateToken() {
        tokenTime = 0
        refreshState()
    }

    /** Returns a usable token, refreshing it via a headless web player if needed. Null if signed out. */
    suspend fun getToken(): String? {
        val t = token
        if (t != null && System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS) return t
        if (!withContext(Dispatchers.Main) { isSignedIn() }) return null
        return refreshLock.withLock {
            val again = token
            if (again != null && System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS) return@withLock again
            refreshHeadless()
        }
    }

    private suspend fun refreshHeadless(): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        pendingToken = deferred
        val web = WebView(appContext)
        configure(web)
        web.loadUrl(PLAYER_URL)
        val result = withTimeoutOrNull(25_000) { deferred.await() }
        pendingToken = null
        Handler(Looper.getMainLooper()).post {
            web.stopLoading()
            web.destroy()
        }
        refreshState()
        result
    }

    /** Shared WebView setup for the sign-in screen and headless refresh. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView, onPage: ((String) -> Unit)? = null) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = Net.BROWSER_UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.addJavascriptInterface(SpotifyTokenBridge, "SyllableBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host.orEmpty()
                if (host.endsWith("spotify.com")) {
                    request.requestHeaders.entries
                        .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
                        ?.value?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                        ?.let { onTokenSeen(it) }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(HOOK_JS, null)
                cm.flush()
                onPage?.invoke(url)
                refreshState()
            }
        }
    }

    /** Hooks fetch / XHR so the web player's bearer token reaches the app even if headers aren't exposed. */
    private const val HOOK_JS = """
(function(){
  if (window.__syllableHooked) return; window.__syllableHooked = true;
  function report(v){ try { if (v && /^Bearer /i.test(v)) SyllableBridge.onToken(v); } catch(e){} }
  var of = window.fetch;
  if (of) window.fetch = function(input, init){
    try {
      var h = init && init.headers, v = null;
      if (h) { v = (typeof h.get === 'function') ? h.get('authorization') : (h['authorization'] || h['Authorization']); }
      if (!v && input && input.headers && typeof input.headers.get === 'function') v = input.headers.get('authorization');
      report(v);
    } catch(e){}
    return of.apply(this, arguments);
  };
  var sh = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(k, v){
    try { if (String(k).toLowerCase() === 'authorization') report(v); } catch(e){}
    return sh.apply(this, arguments);
  };
})();
"""
}

object SpotifyTokenBridge {
    @JavascriptInterface
    fun onToken(value: String) {
        Handler(Looper.getMainLooper()).post { SpotifyAuth.onTokenSeen(value) }
    }
}
