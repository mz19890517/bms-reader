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
        versionCode = 7
        versionName = "1.2.4"
    }

    // 原 Eclipse ADT 目录结构: manifest 在 app/,源码在 app/src/,资源在 app/res/
    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            res.srcDirs("res")
            manifest.srcFile("AndroidManifest.xml")
        }
    }

    // 固定签名:密钥从 CI Secrets 注入(仓库内不出现密钥与密码文件)
    // BMS_KEYSTORE=本地keystore文件路径  SIGNING_STORE_PASSWORD / SIGNING_KEY_PASSWORD=密码
    // 本地无环境变量时退回默认debug签名
    val ksPath = System.getenv("BMS_KEYSTORE") ?: ""
    val storePass = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
    val keyPass = System.getenv("SIGNING_KEY_PASSWORD") ?: storePass
    val fixedSigning = if (ksPath.isNotEmpty() && storePass.isNotEmpty()) {
        signingConfigs.create("fixed") {
            storeFile = file(ksPath)
            storeType = "PKCS12"
            storePassword = storePass
            keyAlias = "bmsreader"
            keyPassword = keyPass
        }
    } else {
        null
    }

    buildTypes {
        debug {
            if (fixedSigning != null) {
                signingConfig = fixedSigning
            }
        }
        release {
            if (fixedSigning != null) {
                signingConfig = fixedSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}
