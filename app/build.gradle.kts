plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.mocktool"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mocktool"
        minSdk = 26
        targetSdk = 30
        versionCode = 24
        versionName = "0.4.2"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk = 30 是刻意选择的：33+ 会强制走自适应图标路径，
        // 在 ColorOS 的 morph 大图标下会出现「图标不满幅」问题。
        // 只关闭这一条检查，其余 lint 规则照常生效。
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // 仅测试期依赖，不会打包进 APK
    testImplementation("junit:junit:4.13.2")
}
