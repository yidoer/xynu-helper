# proguard
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
-dontwarn androidx.security.crypto.**