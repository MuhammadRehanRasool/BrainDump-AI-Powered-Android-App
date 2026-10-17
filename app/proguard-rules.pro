# ML Kit GenAI talks to the AICore system service over Binder with reflection-backed
# protos. Keep it intact rather than risk a release-only crash.
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }
-dontwarn com.google.mlkit.genai.**
-dontwarn com.google.android.gms.internal.mlkit_genai_**

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
