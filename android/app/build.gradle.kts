import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ============================================================
// 正式发布签名（v1.0.0 Release）
// 凭据读取自 android/key.properties（仓库根 .gitignore 已排除，严禁提交）。
// - 以 UTF-8 读取：keystore 可能位于含非 ASCII 字符的绝对路径下
//   （java.util.Properties 默认按 ISO-8859-1 解析，会把中文路径读成乱码）。
// - storeFile 支持绝对路径，也兼容相对 android/app 的相对路径。
// - key.properties 缺失（他人克隆 / CI）→ 自动跳过签名，assembleRelease
//   产出未签名包，不阻塞其他环境构建。
// ============================================================
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { fis ->
            InputStreamReader(fis, Charsets.UTF_8).use { reader -> load(reader) }
        }
    }
}
val releaseStoreFile: File? = keystoreProperties.getProperty("storeFile")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true
if (keystorePropertiesFile.exists() && !hasReleaseSigning) {
    logger.warn(
        "[kuaixia] key.properties 已配置，但 keystore 文件不存在：" +
            "${releaseStoreFile?.absolutePath ?: "(storeFile 未配置)"}。" +
            "本次 release 将产出未签名包，请修正 key.properties 中的 storeFile。",
    )
}

android {
    namespace = "com.kuaixia.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kuaixia.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        // youtubedl-android 携带原生库（Python 运行时 + QuickJS），需覆盖主流 ABI
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // 同时启用 v1/v2/v3 签名：兼顾旧机型安装与 Android 11+ 签名校验
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 存在正式签名配置时启用；否则产出未签名包
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        compose = true
        // AGP 8.x 默认关闭；日志页需要展示 App 版本（BuildConfig.VERSION_NAME）
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // youtubedl-android 要求原生库以未压缩形式打包（等价 extractNativeLibs=true）
            useLegacyPackaging = true
        }
    }

    testOptions {
        // 本地 JVM 单元测试：统一 UTF-8
        unitTests.all {
            it.jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
            it.systemProperty("file.encoding", "UTF-8")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.youtubedl.android)
    implementation(libs.coil.compose)
    implementation(libs.ffmpeg.kit.full)
    // FFmpegKit 运行时必需：ffmpeg-kit-maintained 的 POM 漏声明该传递依赖
    // （否则 NoClassDefFoundError: com.arthenica.smartexception.java.Exceptions）
    implementation(libs.smart.exception.java)
    implementation(libs.smart.exception.common)

    // Phase 3.6：下载任务持久化（Room）
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 本地 JVM 单元测试（ClipboardUrlExtractor 纯 Kotlin）
    testImplementation("junit:junit:4.13.2")
    // SlidesInfoParser 等纯逻辑类使用 org.json；Android framework 的 org.json 在 JVM 单测中为 stub，
    // 需显式引入参考实现（仅测试类路径，不影响 APK）。
    testImplementation("org.json:json:20240303")
}
