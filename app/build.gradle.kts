plugins {
    id("com.android.application")
}

android {
    namespace = "com.ebike.bmsreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ebike.bmsreader"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // 原 Eclipse ADT 目录结构: manifest 在 app/,源码在 app/src/,资源在 app/res/
    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            res.srcDirs("res")
            manifest.srcFile("AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}
