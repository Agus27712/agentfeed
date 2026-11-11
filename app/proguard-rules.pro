# Keep Compose / Kotlin metadata
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# OkHttp / JSON
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Common annotations noise
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animalsniffer.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# Security Crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Timber
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# App models & Engine logic
-keep class agu.analys.model.** { *; }
-keep class agu.analys.config.** { *; }
-keep class agu.analys.engine.** { *; }
-keep class agu.analys.trading.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Ktor 2.3.9: deteksi debugger JVM opsional, bukan API Android.
# IntellijIdeaDebugDetector menangkap Throwable dan mengembalikan false
# ketika ManagementFactory/RuntimeMXBean tidak tersedia. Jangan perluas ke Netty.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Agent Bridge V3: model @Serializable + server di paket ini dipakai saat runtime.
# Tanpa ini, rilis R8 bisa membuang serializer hasil plugin dan bridge gagal saat dipakai.
-keep class agu.analys.bridge.** { *; }
-keepclassmembers class agu.analys.bridge.** { *; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# SLF4J (transitive dependency from ta4j)
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.**

# Ta4j (Technical Analysis Library)
-dontwarn org.ta4j.core.**
-keep class org.ta4j.core.** { *; }

# WorkManager
-dontwarn androidx.work.**
-keep class androidx.work.** { *; }

# Prevent stripping of BuildConfig
-keep class agu.analys.BuildConfig { *; }

# Netty & Ktor Server (Agent Bridge V3)
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn com.google.protobuf.**
-dontwarn com.ning.compress.**
-dontwarn net.jpountz.lz4.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.jboss.marshalling.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.log4j.**

-keep class io.netty.** { *; }
-keep interface io.netty.** { *; }
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep class agu.analys.bridge.** { *; }
-keepclassmembers class agu.analys.bridge.** { *; }

