# proguard
-keepattributes Signature
-keepattributes *Annotation*
# 崩溃堆栈保留行号，便于用 mapping.txt 还原；源文件名统一隐藏
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
-dontwarn androidx.security.crypto.**

# WebView JS 桥：网页通过方法名调用，名字和方法都不能被裁剪/改名
# （默认规则已包含，这里显式写出，防止以后换默认规则文件时丢失）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.xynu.campus.library.web.LibraryJsBridge { *; }

# 代码里会把异常类名（e.javaClass.simpleName）展示给用户/写进日志，
# 保留本应用异常类的类名，避免显示成 a.b.c 之类的混淆名
-keepnames class com.xynu.campus.** extends java.lang.Throwable

# ---------------------------------------------------------------------------
# R8 实测报告缺失的类（此前一开 isMinifyEnabled 就直接构建失败）
#
# 来源：androidx.security:security-crypto 传递依赖 com.google.crypto.tink，
# 而 tink 的源码注解（errorprone）不在运行时依赖里。这 4 条由 AGP 自动生成于
# app/build/outputs/mapping/release/missing_rules.txt，属于注解，运行不需要。
#
# 验证方式：把 app/build.gradle.kts 的 isMinifyEnabled 临时改 true，
# 执行 :app:assembleRelease；补上这 4 条后 R8 能跑完并产出 mapping.txt。
# ---------------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
