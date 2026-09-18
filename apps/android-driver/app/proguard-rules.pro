# Section 25.6: release builds disable screenshot and test hooks, verbose
# provider logging, and remote debugging paths.

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Kotlin serialization needs its generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
