<p align="center"><img src="docs/icon.png" width="120" alt="DragWheel icon"></p>

# DragWheel

Convert and edit files right on your device. Nothing is uploaded.

- **Windows:** hold **Shift** while you drag a file. A wheel appears under the cursor. Drop the file on a slice, and the result is saved next to the original.
- **Android:** share a file to DragWheel from any app (or open the app and choose files). Tap a slice, or press the centre and slide to one. Results go to **Downloads/DragWheel**.

## Download

Go to [Releases → latest](https://github.com/Harismushtaq12/DragWheel/releases/tag/latest).

| Device | File | How to install |
|---|---|---|
| Windows 10 or 11 (64-bit) | `DragWheel.exe` | Run it. If SmartScreen warns you, choose **More info → Run anyway** (the app is not code-signed yet). It sits near the clock. |
| Android 10 or newer | `DragWheel-Android.apk` | Open it on your phone and allow installing from your browser or Files app. |

These files are built automatically by GitHub Actions on every change.

## What it can do

The wheel shows only what fits the file you are holding. On Windows, rest on the centre of the wheel for half a second to switch between **Convert** and **Tools**. On Android, tap the centre or use the switch below the wheel.

### Windows

| File | Convert | Tools |
|---|---|---|
| Image (JPG, PNG, HEIC, WebP, AVIF, GIF, BMP, TIFF, ICO) | JPG, PNG, WebP, AVIF, ICO, BMP, PDF | Compress, Half size, Rotate, Grayscale, Remove metadata, Read QR code |
| PDF | PNG or JPG pages, Merge (2+ PDFs) | Split, Reverse, Rotate, Optimize, Remove info |
| Audio\* | MP3, M4A, WAV, FLAC, OGG, Opus | Normalize volume, Mono, Smaller MP3 |
| Video\* | MP4, WebM, MOV, MKV, GIF, MP3 | Compress, 720p, Mute, Rotate, First frame |
| Archive (ZIP, 7z, RAR, tar.gz…) | Extract | SHA-256 |
| Folder or any file | Zip, tar.gz | SHA-256, Copy path |

\*Audio and video need ffmpeg. Right-click the DragWheel icon near the clock and choose **Install ffmpeg**. It installs with winget, Microsoft's package manager.

### Android

| File | Convert | Tools |
|---|---|---|
| Image | JPG, PNG, WebP, PDF | Compress, Half size, Rotate, Grayscale, Remove metadata, Read QR code |
| PDF | PNG or JPG pages, Merge (2+ PDFs) | Split, Reverse, Rotate |
| Video | MP4, Audio only (M4A), First frame | Compress (720p), Mute, Rotate |
| Audio | M4A | Zip, SHA-256 |
| ZIP | Unzip | SHA-256 |
| Any file | Zip | SHA-256 |

Android uses the phone's own video encoders, so it needs no extra downloads.

## How it works

**Windows** (`windows/DragWheel`, C# / .NET 8 / WPF)

1. `MouseHook.cs` watches the mouse in all apps. When the left button is held, the mouse moves, and Shift is down, it shows the wheel.
2. `WheelWindow.xaml.cs` draws the wheel under the cursor. It stays almost invisible until a file drag actually enters it, so normal Shift-clicks are not disturbed. It always answers the drop as a **copy**, so your original file is never moved or deleted.
3. `Catalog.cs` picks the slices for the dragged files.
4. `Tools/` does the work: Magick.NET for images, PDFsharp and the Windows PDF engine for PDFs, ffmpeg for audio and video, SharpCompress for archives, ZXing for QR codes.

**Android** (`android/`, Kotlin / Jetpack Compose)

1. `MainActivity.kt` receives files from the Share menu or the file picker.
2. `ui/Dial.kt` draws the wheel and handles tap and slide.
3. `core/Catalog.kt` picks the slices.
4. `ops/` does the work: Android's image decoder and PDF renderer, Media3 Transformer for video, PdfBox-Android for PDFs, ZXing for QR codes.

## Add your own action

Add one `WheelAction` to the right list in `Catalog.cs` (Windows) or `core/Catalog.kt` (Android). Give it a short title, a two-word hint, and the function that does the work. Nothing else needs to change.

## Build it yourself

**Windows:** install the [.NET 8 SDK](https://dotnet.microsoft.com/download), then:

```
dotnet run --project windows/DragWheel
```

**Android:** open the `android` folder in Android Studio and press Run, or:

```
cd android
./gradlew assembleDebug
```

## Known limits

- Windows: the wheel holds up to 8 slices. Apps running as administrator cannot drop onto it (a Windows rule).
- Windows: the .exe is not code-signed, so SmartScreen warns on first run.
- Android: audio can only be converted to M4A, because Android has no built-in MP3 encoder.
- Android: the APK is signed with a debug key. Use your own key before publishing on Google Play.

## Privacy

Everything runs on your device. DragWheel has no servers, no accounts and no analytics.

## License

MIT. Third-party libraries keep their own licenses: Magick.NET (Apache-2.0), PDFsharp (MIT), SharpCompress (MIT), ZXing (Apache-2.0), PdfBox-Android (Apache-2.0), AndroidX and Media3 (Apache-2.0). ffmpeg is not bundled.
