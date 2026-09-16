plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.scan.qr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scan.qr"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3"
    }

    // release 也复用 debug 签名：与之前交付的 debug 包**同一张证书**，
    // 手机上可以直接覆盖升级，不需要先卸载。
    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 不混淆（保证 ML Kit / CameraX / Compose 不被 R8 误伤），
            // 但 debuggable=false：去掉 debug 构建的额外校验与解释执行，Compose 明显更顺
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("shared")
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
