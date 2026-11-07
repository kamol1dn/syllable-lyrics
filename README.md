# Syllable

Word-by-word synced lyrics for Android, for whatever is playing.

- **From the media notification** — grant notification access and Syllable follows any player (Spotify, YouTube Music, Chrome, …): title, artist and the live playback position come from the active media session. Tap a line to seek, control playback from the bottom bar.
- **From the microphone** — tap the listen button to identify a song playing around you (Shazam-style), then press **Lyrics**. The match offset is used to start the lyrics in sync; pause or nudge them if needed.
- **Word-level lyrics** — every word fills in as it's sung, with a soft wipe, a slight lift, blurred upcoming lines and an artwork-coloured animated background.

## Lyrics sources

Queried in parallel; real word timing beats line timing beats plain text.

| Source | Timing | Notes |
| --- | --- | --- |
| Spotify | syllable / line | Optional. **Settings → Log in with Spotify** (in-app web sign-in; the web-player token is captured and refreshed automatically). |
| Musixmatch | word ("richsync") / line | Anonymous desktop-app token; sometimes rate-limited. |
| NetEase Cloud Music | word (YRC) / line | Good coverage of western and Asian music. |
| LRCLIB | line (sometimes word) | Always on. |

When only line timing exists, word timing inside each line is estimated from word length.

## Song recognition

`ShazamSignature.kt` is a Kotlin port of the open-source, reverse-engineered Shazam fingerprint (SongRec / shazamio). A unit test checks it is byte-identical to the Python reference on a fixed test signal. Recording is 16 kHz mono; recognition is retried at 4 s, 8 s and 12 s.

## Build

Requires JDK 17+ and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Release signing reads `keystore.properties` (git-ignored) from the project root:

```properties
storeFile=release.jks
storePassword=…
keyAlias=syllable
keyPassword=…
```

Without it, release builds fall back to the debug key.

## Notes

Spotify, Musixmatch, NetEase and Shazam endpoints used here are unofficial and can change or rate-limit without notice. This project isn't affiliated with any of them.
