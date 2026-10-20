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
-keep,includedescriptorclasses class com.fireball1725.librarium.**$$serializer { *; }
-keepclassmembers class com.fireball1725.librarium.** {
    *** Companion;
}
-keepclasseswithmembers class com.fireball1725.librarium.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit
-dontwarn com.google.mlkit.**
