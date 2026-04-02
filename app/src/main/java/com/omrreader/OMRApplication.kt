package com.omrreader

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import android.util.Log

@HiltAndroidApp
class OMRApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (OpenCVLoader.initDebug()) {
            Log.i("OMRApplication", "OpenCV loaded successfully")
        } else {
            Log.e("OMRApplication", "OpenCV initialization failed!")
        }
    }
}
