plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gev.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gev.lite"
        minSdk = 26
        // 34 rather than 35 on purpose: Android 15+ forces edge-to-edge layout for
        // targetSdk 35 apps, which would push the WebView under the status and
        // navigation bars. Targeting 34 keeps the layout predictable.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // WebViewAssetLoader: serves the bundled globe app from a real https:// origin
    // instead of file://, which is what lets Cesium's blob-backed web workers run
    // and gives the page a stable, secure origin.
    implementation("androidx.webkit:webkit:1.12.1")
}
