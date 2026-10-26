# ProGuard/R8 rules for Lyrics Listener
# Goal: Enable code shrinking (remove unused code) WITHOUT obfuscating source code
# so stack traces and debugging remain easy.
#
# IMPORTANT: AndroidX, Google Play Services, Kotlin, and most libraries ship with
# their own consumer ProGuard rules. We don't need to keep them - R8 will
# automatically apply their rules and only keep what's actually needed.

# ============================================
# DISABLE OBFUSCATION (keep all names readable)
# ============================================
-dontobfuscate

# ============================================
# KEEP FLUTTER ENGINE (required - Flutter uses reflection)
# ============================================
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.util.** { *; }

# Keep Flutter plugin registrant
-keep class * extends io.flutter.plugin.common.FlutterPlugin { *; }
-keep class * implements io.flutter.plugin.common.PluginRegistry { *; }

# ============================================
# KEEP YOUR APP'S SOURCE CODE (un-obfuscated)
# ============================================
-keep class dev.optimus.lyricslistener.** { *; }

# ============================================
# KEEP NATIVE METHODS (JNI)
# ============================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================
# KEEP ANDROID COMPONENTS (required - referenced from AndroidManifest.xml)
# ============================================
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.app.NotificationService
-keep public class * extends android.service.notification.NotificationListenerService
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ============================================
# KEEP SERIALIZABLE CLASSES (Gson, JSON, etc.)
# ============================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object readResolve();
    java.lang.Object writeReplace();
}

# Keep classes with @Keep annotation
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ============================================
# KOTLIN SERIALIZATION (used by LyricService)
# ============================================
# Keep @Serializable classes so they can be serialized/deserialized
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <fields>;
    <init>();
}
# Keep companion objects for serialization
-keepclassmembers class *$$serializer {
    <fields>;
    <methods>;
}

# ============================================
# GOOGLE PLAY CORE (deferred components - not used)
# Flutter engine references these but app doesn't use them
# ============================================
-dontwarn com.google.android.play.core.**

# ============================================
# WARNINGS TO IGNORE (safe to ignore)
# ============================================
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn org.slf4j.**
-dontwarn sun.misc.**
-dontwarn com.google.errorprone.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
