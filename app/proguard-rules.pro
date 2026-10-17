# AICore talks to a system service over Binder with reflection-backed protos.
# Keep the SDK intact rather than risk a release-only crash.
-keep class com.google.ai.edge.aicore.** { *; }
-keep class com.google.android.gms.internal.aicore.** { *; }
-dontwarn com.google.ai.edge.aicore.**
-dontwarn com.google.android.gms.internal.aicore.**

# Compile-only annotations referenced by Guava and friends.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.lang.model.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.google.common.**
-dontwarn module-info

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep crash stack traces readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
