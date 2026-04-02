plugins {
    id("com.android.library")
}

android {
    namespace = "org.opencv"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("java/src")
            aidl.srcDirs("java/src")
            res.srcDirs("java/res")
            manifest.srcFile("java/AndroidManifest.xml")
            jniLibs.srcDirs("native/libs")
        }
    }
}
