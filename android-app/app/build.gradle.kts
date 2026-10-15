import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 签名配置
//
// 小米互联 SDK 会校验 APK 签名（AAR 内含 SignatureVerifyFailedException），
// 因此**正式联调时 APK 必须与手环端 rpk 使用同一套签名**，否则通信会被拒。
//
// 签名信息从 `android-app/keystore.properties` 读取，该文件已被 .gitignore
// 排除，不会进入版本库。请自行复制模板并填入你自己的 keystore：
//
//     cp android-app/keystore.properties.example android-app/keystore.properties
//
// 未配置该文件时，debug 包回退到 Android 默认 debug 签名，仅可用于本地开发；
// release 包则为未签名产物。
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.marvis.bandremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marvis.bandremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        val storeFilePath = keystoreProperties.getProperty("storeFile")
        if (!storeFilePath.isNullOrBlank()) {
            create("xms") {
                storeFile = file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("xms")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfigs.findByName("xms")?.let { signingConfig = it }
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---------------------------------------------------------------------
    // 小米穿戴开放能力 SDK（xms-wearable-lib）
    //
    // 该 AAR 是小米官方产物，**未随本仓库分发**。请从官方 CDN 下载互联 demo
    // 包后，取出其中的 AAR 放到 app/libs/ 下：
    //
    //   https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip
    //   解压后取 libs/xms-wearable-lib_1.4_release.aar
    //
    // 注意：本地 AAR 必须用 files(...) 显式引用，fileTree(...) 在 Kotlin DSL
    // 下不会被正确编入 dex（已验证：9 个 dex 中 0 处引用，改成 files 后解决）。
    // ---------------------------------------------------------------------
    val xmsAar = file("libs/xms-wearable-lib_1.4_release.aar")
    if (xmsAar.exists()) {
        implementation(files(xmsAar))
    } else {
        logger.warn(
            "[band-remote] 未找到 app/libs/xms-wearable-lib_1.4_release.aar，" +
                "互联通道将无法编译。请按 README「准备工作」一节从小米官方 CDN 下载。"
        )
    }
}
