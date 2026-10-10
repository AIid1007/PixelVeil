# PixelVeil

Offline Android app that turns a face, a person, their clothing, the background or the whole frame of a video into moving square pixels.

- **Targets:** Full frame, Head, Person, Clothing, Background
- **Amount slider:** default 70%, Reset to default
- **Modes:** Privacy (coarse pixels, extra margin, whole frame covers if tracking is lost) and Style
- **Voice:** Off, Deeper, Deep, High (pitch change)
- **Export:** MP4 at source resolution and frame rate, saved to Movies/PixelVeil; GPS, XMP and device tags dropped
- **Share:** Instagram, YouTube, or the system share sheet (includes Edits)
- **Offline:** the app has no internet permission. CI fails the build if one appears.

## Install the latest build

1. On your Android phone (Android 10+), open this repo's **Releases** page → **PixelVeil latest build** → download `PixelVeil.apk`.
2. Open the file. Allow "install unknown apps" for your browser or Files app when asked.

## How it works

Each frame is decoded by Media3, a 256 px copy is segmented on-device with MediaPipe (selfie multiclass model: background, hair, body skin, face skin, clothes, other), the result becomes a block grid anchored to the frame, and a GPU shader paints each marked block with its average colour. The same effect runs in the live preview (ExoPlayer) and in export (Transformer).

Key files:

- `engine/PixelateEffect.kt` — Media3 GL effect: readback, analysis, grid upload, shader
- `engine/GridBuilder.kt` — mask → block grid, Privacy motion guard, growth, fail-safe
- `engine/MaskEngine.kt` — MediaPipe segmenter + face detector, Android face detector fallback
- `export/Exporter.kt` — Transformer export, pitch, metadata stripping
- `ui/Screens.kt` — Home, Editor, Exporting, Done

## Build it yourself

Models are not committed. Download them into `app/src/main/assets/` (URLs are in `.github/workflows/build.yml`), then open the project in Android Studio and run, or `./gradlew assembleRelease`.
