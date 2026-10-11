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

# --- Motor de virtualização (Bcore/NewBlackbox, Apache-2.0) ---
# Espelha as regras do app de demo do upstream: sem elas o R8 renomeia o
# runtime black-reflection e as interfaces anotadas/proxies gerados, e o
# BlackBoxCore.<clinit> falha ("Not found @BlackClass or @BlackStrClass").
-keep class top.niunaijun.blackbox.** {*; }
-keep class top.niunaijun.jnihook.** {*; }
-keep class top.niunaijun.blackreflection.** {*; }
-keep class mirror.** {*; }
-keep class me.weishu.reflection.** {*; }
-keep @top.niunaijun.blackreflection.annotation.BClass class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassName class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * {*;}
-keepclasseswithmembernames class * {
    @top.niunaijun.blackreflection.annotation.BField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldSetNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructor.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructorNotProcess.* <methods>;
}
-dontwarn top.niunaijun.**
-dontwarn black.**
-dontwarn mirror.**
-dontwarn me.weishu.reflection.**

# --- GZIP (java.util.zip) não precisa de regras: é parte do runtime Android ---

# --- Apache Commons Compress (TAR streaming) ---
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.parallel.**
