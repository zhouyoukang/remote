plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dao.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dao.remote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // WebRTC — P2P视频+数据通道
    implementation("org.webrtc:google-webrtc:1.0.32006")

    // OkHttp — WebSocket信令
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson — JSON序列化
    implementation("com.google.code.gson:gson:2.10.1")

    // ZXing — 房间码QR生成
    implementation("com.google.zxing:core:3.5.3")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
