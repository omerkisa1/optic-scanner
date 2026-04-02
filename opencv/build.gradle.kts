plugins {
    id("com.android.library")
}

android {
    namespace = "org.opencv"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            res.srcDirs("res")
            manifest.srcFile("AndroidManifest.xml")
        }
    }
}