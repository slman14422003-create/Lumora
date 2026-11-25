plugins {
    id("com.android.application")
}

val releaseStoreFile = System.getenv("LUMORA_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
val releaseStorePassword = System.getenv("LUMORA_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("LUMORA_KEY_ALIAS")
val releaseKeyPassword = System.getenv("LUMORA_KEY_PASSWORD")
val canSignRelease = releaseStoreFile != null &&
    !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "com.lumora.flashlight"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumora.flashlight"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    testImplementation("junit:junit:4.13.2")
}
