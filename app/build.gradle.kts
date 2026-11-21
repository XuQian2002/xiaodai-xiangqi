plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "daidaixiangqi.app"
    compileSdk = 34

    defaultConfig {
        // 安装包标识（包名）改为「小呆象棋终极版」的拼音形式；桌面显示名仍由 app_name 决定。
        // Android 包名只接受字母数字，中文无法作为 applicationId 上架安装，故用拼音。
        applicationId = "xiaodaixiangqi.zhongjiban"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        // 仅打包 arm64-v8a（目标真机架构）。
        // 纯净源码版不含任何 .so 原生库，此配置保留以便日后补充原生库时限定架构。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 纯净源码版不含签名文件（.keystore 属二进制），默认不签名。
        // 需要出正式安装包时，自行创建 keystore 并在此按下方注释位置补上即可。
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // signingConfig = signingConfigs.getByName("release")  // 补齐签名后取消注释
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // 若日后补充原生库（引擎可执行文件等），需要解压到 nativeLibraryDir 才能被 ProcessBuilder 调用。
            useLegacyPackaging = true
        }
        resources {
            // 排除编译期生成、运行时不读取的构建元数据与调试探针文件；
            // META-INF/services 服务注册保留，不在排除范围内。
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/com/android/build/gradle/app-metadata.properties",
                "META-INF/version-control-info.textproto"
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
