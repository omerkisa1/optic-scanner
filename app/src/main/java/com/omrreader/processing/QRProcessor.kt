package com.omrreader.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QRProcessor @Inject constructor() {
    
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    suspend fun decode(bitmap: Bitmap, region: android.graphics.Rect): String? {
        return try {
            // Crop QR region
            val cropped = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
            val image = InputImage.fromBitmap(cropped, 0)
            
            val barcodes = scanner.process(image).await()
            if (barcodes.isNotEmpty()) {
                barcodes.first().rawValue
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
