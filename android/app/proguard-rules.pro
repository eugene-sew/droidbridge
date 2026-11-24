# BouncyCastle keep rules
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Kotlinx Serialization keep rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.droidbridge.app.**$$serializer { *; }
-keepclassmembers class com.droidbridge.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.droidbridge.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ZXing
-dontwarn com.google.zxing.**
