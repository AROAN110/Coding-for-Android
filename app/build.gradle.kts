plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.AROAN110.CodingAndroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.AROAN110.CodingAndroid"
        minSdk = 26
        // targetSdk=28：Android 10+ 的 W^X 限制（禁止执行应用私有目录文件）
        // 只作用于 targetSdk≥29；28 为 Termux 同款策略，proot/Alpine 依赖它才能运行。
        targetSdk = 28
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
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
}

dependencies {
    // MVP 阶段刻意保持零第三方依赖：先验证「终端 + 双环境 + pmc」最小底座。
    // 本项目采用 GPL-3.0（与参考项目 termux-app / AndroidIDE 的许可兼容）；引入依赖须为 GPL 兼容许可。
}