plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaoyao.autocheckin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaoyao.autocheckin"
        minSdk = 26
        targetSdk = 34
        // 与 GitHub Release 的 tag 对齐（v2.9）。关于页的「检查更新」拿 versionName 比大小，
        // 每发一版记得同时抬这两个值，否则新包会被判成「已是最新」。
        versionCode = 29
        versionName = "2.9"
    }

    buildTypes {
        release {
            // 体积压到最小：代码压缩 + 资源裁剪
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用 debug 签名，方便直接 adb install 调试（正式发布请换自己的 keystore）
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 零第三方依赖：不引入 androidx，APK 更小、无版本兼容坑
    packagingOptions {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 故意留空：纯 Android framework API 实现
}
