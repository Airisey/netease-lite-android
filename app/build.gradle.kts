import java.util.Properties

// 网易云音乐 Lite · Android 应用模块
//
// 版本口径来自用户指定（m38831）：
//   minSdk 26（Android 8.0）/ targetSdk 36 / compileSdk 36
//   applicationId com.netease.lite，versionCode 6，versionName 1.1.4
// 竖屏为主且不支持横屏 —— 屏幕方向锁在 Manifest 里（screenOrientation="portrait"），
// 这样折叠屏展开、平板横置都不会把界面拉变形。
//
// AGP 9 自带 Kotlin 支持（内置 kotlin 扩展），所以这里**不能**再单独 apply
// org.jetbrains.kotlin.android —— 会报 "Cannot add extension with name 'kotlin'"。
// Compose 编译器插件仍然要显式加。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 签名。口令放在 keystore/keystore.properties 而不是写在这个文件里：
// 这个仓库不是公开的，但构建脚本经常被人复制粘贴，密钥口令跟着文件一起流出去
// 比丢一个 2200 字节的 keystore 严重得多。properties 文件不存在时 release 就用
// 调试签名兜底 —— 那样装出来的包也能跑，只是不能用来覆盖安装正式包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.netease.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netease.lite"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.1.5"
    }

    androidResources {
        // 只随包带中文资源。源应用也没有多语言，声明出来免得系统按语言回落到英文兜底。
        localeFilters += listOf("zh")
    }

    signingConfigs {
        create("release") {
            val store = rootProject.file("keystore/netease-lite.p12")
            if (store.exists()) {
                storeFile = store
                storePassword = keystoreProps.getProperty("storePassword") ?: "netease-lite"
                keyAlias = keystoreProps.getProperty("keyAlias") ?: "netease-lite"
                keyPassword = keystoreProps.getProperty("keyPassword") ?: "netease-lite"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            // 不混淆：这个应用没有需要隐藏的商业逻辑，开混淆只会让线上崩溃栈变成乱码，
            // 而 Compose / 反射取字段的场景也更容易被默认规则误伤。
            isMinifyEnabled = false
            // keystore 在就签正式包，不在就退回 debug 签名（见文件上方注释）。
            signingConfig =
                if (rootProject.file("keystore/netease-lite.p12").exists()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 播放内核 + 媒体会话。MediaSessionService 一次性解决用户要求的四件事：
    // 后台播放、通知栏控制（含进度与封面）、锁屏控制、蓝牙耳机按键。
    // 音频焦点由 ExoPlayer 的 setAudioAttributes(handleAudioFocus = true) 接管。
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    // 网络。OkHttp 同时供 Coil 复用同一个连接池（Coil 2.7 自己也是这条线）。
    // 钉在 4.12.0 是必须的：OkHttp 5.x 的 okhttp-android 要求 compileSdk ≥ 37，
    // 而用户把 compileSdk 指定成 36，升上去就违背口径。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 图片。源应用走主进程代理绕 neimg 的 502，Android 侧改成给 Coil 加 Referer 头。
    implementation("io.coil-kt:coil-compose:2.7.0")

    // SAF 目录索引（「本地扫描」用 ACTION_OPEN_DOCUMENT_TREE）。
    implementation("androidx.documentfile:documentfile:1.0.0")
}
