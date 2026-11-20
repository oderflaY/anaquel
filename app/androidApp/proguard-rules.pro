# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ---- Anaquel ----
# kotlinx.serialization: conservar serializadores generados.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-keep class com.eter.mitienda.**$$serializer { *; }
# Ktor / coroutines
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-keep class io.ktor.client.engine.** { *; }
# WorkManager crea los workers por reflexión.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
# ML Kit (lector de códigos): el resultado del detector nativo llega como protobuf
# lite, que se lee por reflexión con los nombres de sus campos. Si R8 los renombra,
# la app se cierra justo al detectar un código.
-keep class com.google.photos.vision.barhopper.** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class * extends com.google.android.gms.internal.mlkit_vision_barcode_bundled.zzed { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
