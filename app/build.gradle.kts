plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neotv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neotv.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
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
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Moteur d'images ultra-rapide pour Logos TV & Affiches Films/Séries
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Moteur vidéo ultra-fluide Media3 ExoPlayer (HLS / TS / MP4 / MKV)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
}
