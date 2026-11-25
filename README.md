# LUMORA ✦ Smart Flashlight

A premium dark Android flashlight with a custom launcher icon, branded splash screen, manual brightness control on compatible hardware, and ambient-light AUTO mode.

## What's new in 1.2.0
- SOS and strobe light modes (run while the app is open; a photosensitivity warning is shown).
- Auto-off timer (5/15/30/60 min) that also applies to the Quick Settings tile.
- Quick Settings tile for the torch (Android 7.0+).
- Optional white screen light when the device has no flash (Settings).
- All text moved to `strings.xml` with an Arabic translation (`values-ar`), RTL-safe layout, and TalkBack labels for status views.
- All colors now live in `colors.xml`.
- Version is 1.2.0 (versionCode 3) and matches `app/build.gradle.kts`.

## Earlier changes
- 1.1.x: cyan/blue visual system, adaptive icon, Android 12+ splash, ambient-light AUTO mode, GitHub Actions build and release workflow, build fixes (compileSdk 36, Core KTX 1.17.0).

## Requirements
- Android Studio with Android SDK Platform 36
- JDK 17
- Gradle 9.6.0 (the GitHub workflow provisions it automatically)

## Build locally
This source archive does not include a Gradle Wrapper binary. In Android Studio, open the project and use its configured Gradle installation, or install Gradle 9.6.0 and run:

```bash
gradle clean assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## GitHub Actions
The workflow is at `.github/workflows/android.yml`.
- Runs on pushes to `main`/`master`, pull requests, and manual dispatch.
- Builds `assembleDebug` and `assembleRelease`.
- Uploads both APK outputs as workflow artifacts.
- On a tag such as `v1.2.0`, creates a GitHub Release and attaches the debug APK.

To publish a production APK, configure Android signing with a persistent keystore and GitHub Actions secrets. The release build produced by this default workflow is unsigned; the debug APK is signed with Android's debug key and is intended for testing, not Play Store distribution.

## AUTO mode behavior
LUMORA uses Android's ambient light sensor (`TYPE_LIGHT`) rather than opening the camera continuously. This avoids fighting the camera flash/torch hardware and allows light readings while the torch is active.
- At or below 35 lux: switch on at maximum.
- 36–219 lux: adjust to a mid-level when torch strength is supported.
- At or above 220 lux: switch off.

Thresholds are conservative defaults, not medical or safety guarantees. Sensor placement and calibration differ by phone. Users should keep the sensor unobstructed.

## Hardware limitations
- Torch strength control is available only on compatible devices running Android 13+.
- On devices without torch-strength support, the flash can only be switched on/off; the slider cannot change physical LED output.
- Some phones do not include an ambient light sensor or camera flash.
- The CAMERA permission is optional and requested only when you turn on camera light sensing. Frames are measured in memory and never saved or sent. Torch control itself does not need it.

## Project structure
```text
LUMORA/
├── .github/workflows/android.yml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/lumora/flashlight/   (activity, screen, auto mode, tile, timer)
│       └── res/
│           ├── drawable/
│           ├── mipmap-anydpi-v26/
│           ├── values/        (colors.xml, strings.xml, styles.xml)
│           └── values-ar/     (Arabic strings)
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Publish a tag
```bash
git tag v1.2.0
git push origin v1.2.0
```

The workflow creates a GitHub Release when the tagged build succeeds.


## Build compatibility
The project compiles against the stable Android 16 SDK (`compileSdk 36`) with AndroidX Core KTX `1.17.0`. The CI workflow installs `platforms;android-36` and `build-tools;36.0.0` and does not request the preview platform 37.
