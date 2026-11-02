# Music Cut

A **free, ad-free, and offline** music trimming/cutting app for Android.

- Pick a song, then set the start and end points with the sliders
- Preview the selected range before trimming
- Save the result as `.m4a` (AAC) in the `Music/MuzikKirp` folder
- No internet permission, no ads, no tracking, no external libraries

It supports any audio format (mp3, m4a, wav, ogg, flac…) that Android's built-in codecs can decode.

## Installation (APK)

**Option 1: Releases (recommended)**

1. Go to the **Releases** section of this repository.
2. Download `app-debug.apk` from the latest release.
3. Open the file on your phone. If asked, allow installing from unknown sources.

**Option 2: Latest build from Actions**

1. Open the latest successful "APK creator" run in the **Actions** tab.
2. Download `music-cut-apk` from the **Artifacts** section at the bottom and unzip it.
3. Send `app-debug.apk` to your phone and open it. If asked, allow installing from unknown sources.

Requirement: Android 10 (API 29) or higher.

## Build it yourself

You need JDK 17 and the Android SDK installed:

```bash
gradle assembleDebug
```

The APK will be created at `app/build/outputs/apk/debug/app-debug.apk`.

## How it works

The selected range is decoded with Android's `MediaCodec` into a temporary PCM file, then encoded to AAC and written to an `.m4a` file using `MediaMuxer`. Because the audio is re-encoded once during trimming, there may be a very small quality loss (192 kbps AAC).

## To do

- [ ] Lossless trimming (skip re-encoding for AAC/M4A input)
- [ ] Output format selection
- [ ] Fade in / fade out
- [ ] App icon
- [ ] Waveform display

## Contributing

Bug reports and pull requests are welcome. For larger changes, it's a good idea to open an issue first.

## License

[MIT](LICENSE)
