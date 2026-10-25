plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.evtv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.evtv.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    // Sabit imza: her yeni sürüm eskisinin üzerine kurulur, favoriler silinmez
    signingConfigs {
        create("evtv") {
            storeFile = file("evtv.keystore")
            storePassword = "evtv1234"
            keyAlias = "evtv"
            keyPassword = "evtv1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("evtv")
        }
        release {
            // Kodu küçültüp optimize et: menüler daha akıcı, APK daha küçük
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("evtv")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Lint uyarıları derlemeyi durdurmasın
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Video oynatıcı
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    // Dolby (AC3/E-AC3), DTS, TrueHD gibi TV'nin çözemediği sesler için yazılım çözücü
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Kanal logoları
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Yan yüklenen (Play Store dışı) kurulumda da hızlı açılış profili
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
