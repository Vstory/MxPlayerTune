import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名来源：local.properties（已被 .gitignore 忽略）。
//   CI 从仓库 secrets 解出固定 keystore 后写入这四个键；本机构建不带该文件时
//   不挂签名（debug 回落 AGP 调试签名、release 产出 unsigned）—— CI 会断言产物已签名，
//   防止静默把 unsigned 包发出去。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signStoreFile = localProps.getProperty("storeFile")
val hasSigning = !signStoreFile.isNullOrBlank()

android {
    namespace = "io.github.vstory.hook.mxplay"
    compileSdk = 37
    // 本机只装了 arm64 build-tools 37.0.0（Commit451 版）；不指定则 AGP 会去装 x86_64 的默认版本
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.vstory.hook.mxplay"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
                // 签名方案显式指定（不靠 AGP 默认，否则随 minSdk/版本变动而漂移）：
                //   v1（JAR 签名）关：minSdk 26 = Android 8，v1 只对 API < 24 有意义
                //   v2 开：API 24+ 的校验路径
                //   v3 开：**AGP 默认不开**，需显式启用（API 28+；带签名的密钥轮换与更严的校验）
                //   v4 关（增量安装用，会额外产出 .idsig，本项目不分发它）
                // CI 会断言产物确实同时具备 v2 + v3（见 .github/workflows/build.yml 的 Verify 步）。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        debug {
            // 与正式版**共用同一把签名** ⇒ 两个变体可互相覆盖安装（同包名 + 同签名，直接覆盖即可，
            // 不必先卸载；而卸载会丢掉 LSPosed 里的「已启用 + 已勾作用域」状态，是本项目反复装包时最费事的一步）。
            // 未提供固定密钥时（本机不带 local.properties 的开发构建）不挂，回落 AGP 调试签名。
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 便于在设备上分辨装的是哪一个（应用信息 / LSPosed 模块列表都看得到）
            versionNameSuffix = "-debug"
        }
        release {
            // R8 开（release 专属，debug 保持不混淆以便排障）：
            //   ① 裁掉 okhttp/okio 里用不到的部分（本模块对这一层只要「一个 PROPFIND + 取流 + 自签放宽」）；
            //   ② 自动消除 `if (BuildConfig.DEBUG)` 恒假分支 —— 这正是断言 ⑦ 要的（javac 删不掉
            //      「写在独立方法里」的 [DBG] 字符串常量，2026-09-29 真漏过一次）。
            // keep 规则的三类名字见 app/proguard-rules.pro（写在资源/清单里、按名反射加载）
            // **门禁不撤**：tools/dex_trim_check.py 仍读产物字节断言，不依赖「R8 一定会删」。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // libxposed 本地 jar（api102）：api=编译期 + interface/service=运行期
    compileOnly(files("libs/libxposed/api.jar"))
    implementation(files("libs/libxposed/interface.jar"))
    implementation(files("libs/libxposed/service.jar"))

    // WebDAV 传输：OkHttp **3.14.9（纯 Java）**。
    //   为什么必须是它、为什么不上 4.x/5.x：见 WebDavClient 类注释与 CHANGELOG ——
    //   平台的 setRequestMethod 有动词白名单（PROPFIND 被拒），而 Android 的 HttpsURLConnection
    //   是委托壳（写壳不生效 ⇒ 请求以 POST 出去 ⇒ 服务端 501）。OkHttp 无白名单 ⇒ 该类问题消失。
    //   版本：3.14.9 的 okhttp+okio dex 实测 441 KB（真机包 +361 KB）；
    //        4.12.0 的 okhttp+okio-jvm 单独就 1.08 MB（2.56 倍）⇒ 这一层只发一个 PROPFIND，取小的。
    implementation("com.squareup.okhttp3:okhttp:3.14.9")
}
