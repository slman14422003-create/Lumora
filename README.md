# LUMORA ✦ Smart Flashlight

A premium dark Android flashlight with a custom launcher icon, branded splash screen, manual brightness control on compatible hardware, and ambient-light AUTO mode.

## What's new in 1.1.0
- Refined premium interface with a cyan/blue luminous visual system.
- Custom adaptive Android launcher icon and branded Android 12+ splash screen.
- Native Android torch control and ambient-light sensor support.
- GitHub Actions workflow builds debug and release APKs on pushes, pull requests, and manual runs.
- Pushing a `v*` tag automatically creates a GitHub Release with an installable debug APK.

## Requirements
- Android Studio with Android SDK Platform 37
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
- On a tag such as `v1.1.0`, creates a GitHub Release and attaches the debug APK.

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
- Android requires the CAMERA permission for flashlight access. LUMORA does not capture or save photos/video.

## Project structure
```text
LUMORA/
├── .github/workflows/android.yml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/lumora/flashlight/MainActivity.kt
│       └── res/
│           ├── drawable/
│           ├── mipmap-anydpi-v26/
│           └── values/
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Publish a tag
```bash
git tag v1.1.0
git push origin v1.1.0
```

The workflow creates a GitHub Release when the tagged build succeeds.


## Build fix (1.1.1)
The CI build now compiles against Android API 37 because AndroidX Core KTX 1.19.1 requires compileSdk 37 or newer. The workflow installs Android SDK Platform 37 and Build Tools 37.0.0.


## Build compatibility fix (v1.1.2)

This project uses the stable Android 16 SDK platform (`compileSdk 36`) and
AndroidX Core KTX `1.17.0`. The CI workflow installs `platforms;android-36`
and `build-tools;36.0.0`. It intentionally does not request the Android SDK
Preview platform 37, which may not be available on standard GitHub runners.
