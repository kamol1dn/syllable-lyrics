# Syllable

Syllable is an Android app that shows the lyrics of the song you're listening to and highlights each word as it's sung.

<p>
  <img src="docs/screenshots/now-playing.jpg" width="240" alt="Lyrics for the song playing, with the current word lighting up">
  <img src="docs/screenshots/listen.jpg" width="240" alt="Listening for a song through the mic">
  <img src="docs/screenshots/settings.jpg" width="240" alt="Settings, with Spotify sign-in and lyrics sources">
</p>

<img src="docs/screenshots/landscape.jpg" width="732" alt="Landscape: album art and controls on the left, lyrics on the right">

The song in the screenshots is "Amazing Grace", whose words are in the public domain. The cover is a test image.

## Install

Syllable isn't on Google Play. You install it from the APK file on the releases page. It needs Android 8.0 or newer.

1. On your phone, open the [latest release](https://github.com/kamol1dn/syllable-lyrics/releases/latest) and download `Syllable-x.y.z.apk`.
2. Open the downloaded file. The first time, Android asks you to let your browser or file manager install apps. Allow it and go back.
3. Tap **Install**.
4. Open Syllable and tap **Allow notification access**. That is how it sees which song is playing. On Android 13 and newer the switch can be greyed out for an app installed from a file: open the app's **App info** page, tap the three dots at the top right, choose **Allow restricted settings**, then turn the switch on. Syllable shows this hint when you need it.

To update, install a newer APK over the old one. Your settings stay.

### If Google Play Protect blocks the install

Play Protect doesn't know Syllable, because it comes from outside Google Play and its developer isn't registered with Google. An app that asks for notification access gets extra attention, and Syllable can't work without it. So you may see a warning, or the install may be refused.

- If the warning has **More details** or **Install anyway**, use that.
- Newer Android versions have a one-time setting that allows apps from unverified developers. If the install is refused outright, Android points you to it.
- With a computer you can install over USB instead: `adb install -r Syllable-x.y.z.apk`.

You don't need to turn Play Protect off. If you did turn it off to get Syllable installed, turn it back on afterwards; Syllable keeps working.

If you'd rather not trust a prebuilt file, the whole source is here and you can [build it yourself](#build). Every release APK is signed with the same key, whose SHA-256 fingerprint is:

```
5E:1C:84:E9:B0:82:4A:6B:4C:63:84:AE:02:9B:1A:E3:D9:7B:B7:F3:28:E3:BC:FD:AF:96:20:54:0D:A1:3D:8D
```

## Features

- **Now playing.** Give Syllable notification access and it picks up the song from any music app (Spotify, YouTube Music, Chrome and others), along with how far into the song you are. Tap a line to jump to it. The bar at the bottom has play, pause and skip.
- **Identify a song.** Tap the listen button and Syllable uses the mic to find the song playing near you, like Shazam. The lyrics open at the right spot as soon as it finds a match. While they are on screen it listens again about once a minute, and more often near the end, to stay in time and to catch the next song. You can turn that off in Settings. Tap the listen button on the lyrics to check again right away.
- **Word by word.** Each word fills in as it's sung, and long notes glow. Lines move in a soft wave as the song goes on.
- **Timing per device.** Syllable knows whether you're listening on the phone speaker, wired or USB headphones, Bluetooth, or casting to another device. If the lyrics run early or late, shift them once with the timing button and Syllable remembers it for that device. Bluetooth devices start with lyrics 0.2s later, since Bluetooth audio arrives a little late. You can change that default, or forget a saved device, in Settings.
- **Duets and background vocals.** With Spicy Lyrics, a second singer's lines sit on the right, and background vocals fill in under the main line at their own pace.
- **Moving background.** The album art is blurred into a slowly flowing gradient, like the Spicy Lyrics extension for Spotify. It slows down when the music is paused. For songs found with the mic, Syllable uses Spotify's album art when you're signed in.
- **Landscape.** Turn your phone sideways for a full-screen view with the album art and controls next to the lyrics. It suits a phone on a stand.

## Where the lyrics come from

Syllable tries these in order and uses the first one with timing for each word. If none has it, it uses the first one timed by line, then plain text. It asks them all at once, so this doesn't slow anything down.

| Source | Timing | Notes |
| --- | --- | --- |
| Spicy Lyrics | Word or line | Community syncs, then Apple Music and Spotify. Has duets and background vocals. Needs an API key (see below). |
| Spotify | Word or line | Optional. Sign in from **Settings → Log in with Spotify**. Syllable keeps you signed in. |
| Musixmatch | Word or line | Works without an account. It sometimes stops answering for a while. |
| NetEase Cloud Music | Word or line | Has lots of Western and Asian songs. |
| LRCLIB | Line, sometimes word | Always on. |

If a song only has line timing, it lights up line by line; no word timing is made up.

### Spicy Lyrics key

Spicy Lyrics needs a free key from [developers.spicylyrics.org](https://developers.spicylyrics.org). You can paste your own in **Settings → Spicy Lyrics**, or build it into the app by putting it in `spicy.properties` in the project root. Git ignores this file.

```properties
key=sl_pk_...
```

A key that ships inside an app should be a client key (`sl_pk_`). Make one under **Client access** on your application page, with "Allow requests with no Origin header" turned on, since Android apps don't send that header. The end of the lyrics shows where they came from, and for community syncs, who uploaded and made them. The Spicy Lyrics terms ask for this. They also limit how long lyrics can be kept, so Syllable clears cached lyrics after 30 days.

## Song recognition

`ShazamSignature.kt` is a Kotlin version of the open-source Shazam fingerprint code from SongRec and shazamio. A unit test checks that it gives exactly the same output as the Python version on a test signal. Syllable records at 16 kHz mono and tries to find a match after 4, 8 and 12 seconds.

## Build

You need JDK 17 or newer and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Release builds are signed with the key named in `keystore.properties` in the project root. Git ignores this file.

```properties
storeFile=release.jks
storePassword=…
keyAlias=syllable
keyPassword=…
```

If the file is missing, release builds use the debug key.

Releases are built by GitHub Actions. Pushing a tag like `v1.2.1` runs the tests, builds the signed APK and publishes it with the notes in `docs/releases/`.

## Notes

The background follows the approach of [Kawarp](https://github.com/better-lyrics/kawarp) (MIT), the renderer behind Spicy Lyrics' background. On Android 12 and older, the blurred art turns slowly instead.

Spicy Lyrics is used through its public developer API. Syllable uses unofficial Spotify, Musixmatch, NetEase and Shazam endpoints. They can change or limit requests at any time. This project isn't connected to any of these companies.
