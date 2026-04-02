# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Apache POI (Excel)
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.xmlbeans.**
-keep class org.openxmlformats.** { *; }
-dontwarn org.openxmlformats.**
-keep class schemasMicrosoftComVml.** { *; }
-dontwarn schemasMicrosoftComVml.**
-keep class schemasMicrosoftComOfficeExcel.** { *; }
-dontwarn schemasMicrosoftComOfficeExcel.**
-keep class schemasMicrosoftComOfficeOffice.** { *; }
-dontwarn schemasMicrosoftComOfficeOffice.**

# iText (PDF)
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Room
-keep class * extends androidx.room.RoomDatabase { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
