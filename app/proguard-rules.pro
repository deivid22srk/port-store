# Port Store R8 rules

# --- JNI bridge: never touch native methods / the core package ---
-keep class com.deivid22srk.portstore.core.** { *; }
-keepclassmembers class com.deivid22srk.portstore.core.** { native <methods>; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.deivid22srk.portstore.**$$serializer { *; }
-keepclassmembers class com.deivid22srk.portstore.** {
    *** Companion;
}
-keepclasseswithmembers class com.deivid22srk.portstore.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Coil ---
-dontwarn coil.**

# --- android-youtube-player (bridge JS do WebView + custom-ui com findViewById) ---
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }

# Keep line numbers for crash readability
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
