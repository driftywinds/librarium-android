# Retrofit + OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface retrofit2.Call
-keep,allowobfuscation,allowshrinking interface retrofit2.http.**
-dontwarn javax.annotation.**

# kotlinx.serialization — generated serializers are referenced reflectively
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.dwprod.librariumscanner.**$$serializer { *; }
-keepclassmembers class com.dwprod.librariumscanner.** {
    *** Companion;
}
-keepclasseswithmembers class com.dwprod.librariumscanner.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit
-dontwarn com.google.mlkit.**

# ML Kit and CameraX discover implementations through manifest meta-data and
# reflection, not through direct references, so an obfuscated build can keep
# optimising them away. These are the keeps a shrunken build needs; the release
# variant ships unminified for now (see app/build.gradle.kts), so this is the
# rule set for `-PminifyRelease=true`.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class androidx.camera.** { *; }

