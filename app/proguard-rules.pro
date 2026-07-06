# Add project specific ProGuard rules here.

# Keep RootEncoder classes
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# Keep Netty (used by RootEncoder)
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Keep DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
