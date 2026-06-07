# Keep JNI-called methods.
-keepclassmembers class com.lavazombie.amazegame.CoreBridge {
    native <methods>;
}
# Keep Ktor server classes wired via reflection.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
