# Preserve line numbers for better crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Firestore keep rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.firebase.firestore.** { *; }

# Keep data classes used with Firestore
-keep class com.xenonware.notes.viewmodel.classes.** { *; }
-keepclassmembers class com.xenonware.notes.viewmodel.classes.** {
    <init>(...);
    public <fields>;
    public <methods>;
}

# Keep enums used with Firestore/Serialization
-keepclassmembers enum com.xenonware.notes.viewmodel.classes.** {
    *;
}

# Kotlinx Serialization keep rules
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.json.** { *; }
-keepclassmembers class com.xenonware.notes.viewmodel.classes.** {
    *** Companion;
}
-keep @kotlinx.serialization.Serializable class com.xenonware.notes.viewmodel.classes.** { *; }

# Desert Ant Labs JNI & Native Bindings
-keep class ai.desertant.** { *; }
-keepclassmembers class ai.desertant.** {
    native <methods>;
    *;
}
-keepnames class ai.desertant.**
-keep interface ai.desertant.** { *; }

# LiteRT / TensorFlow Lite Runtime & Delegates
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
