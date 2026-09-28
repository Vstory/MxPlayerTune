plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.vstory.hook.mxplay"
    compileSdk = 37
    // 本机只装了 arm64 build-tools 37.0.0（Commit451 版）；不指定则 AGP 会去装 x86_64 的 36.0.0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.vstory.hook.mxplay"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
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
