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
            isMinifyEnabled = false
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
}
