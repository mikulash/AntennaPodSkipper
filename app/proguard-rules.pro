# Keep llama.cpp JNI classes
-keep class de.danoeh.antennapod.llama.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
