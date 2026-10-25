# Hata raporlarında satır numaraları görünsün
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# FFmpeg ses çözücü: yansıma ve yerel (JNI) kod ile yüklenir
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Media3 eklentileri yansıma ile yükler
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-dontwarn org.jellyfin.**
-dontwarn androidx.media3.**
