# JavaScript bridge used by the Spotify sign-in WebView.
-keepclassmembers class dev.kamol1dn.syllable.spotify.SpotifyTokenBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn org.slf4j.**
