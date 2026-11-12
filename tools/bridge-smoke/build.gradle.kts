plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }

// Versi dependensi dibaca dari gradle/libs.versions.toml milik aplikasi, BUKAN ditulis ulang di
// sini. Duplikasi versi membuat smoke bisa hijau sementara aplikasi memakai kombinasi lain —
// persis pola yang menyembunyikan NoSuchMethodError kotlinx-coroutines/Ktor 2.3.9 (2026-11-04).
val appVersions = rootDir.resolve("../../gradle/libs.versions.toml").readText()
fun tomlVersion(key: String): String =
    Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(appVersions)?.groupValues?.get(1)
        ?: error("Kunci versi '$key' tidak ditemukan di gradle/libs.versions.toml")

val ktorVersion = tomlVersion("ktor")
val coroutinesVersion = tomlVersion("kotlinxCoroutinesCore")
val serializationVersion = tomlVersion("kotlinxSerialization")

// Salin apa adanya berkas produksi yang diuji ke source set smoke.
// Keempatnya murni Kotlin + kotlinx.serialization (kelas Android diberi stub di
// src/main/kotlin), supaya kontrak HTTP/JSON yang diuji IDENTIK dengan produksi.
// Jangan menambah berkas yang bergantung Android ke daftar ini tanpa stub baru.
val copyRealSources = tasks.register<Copy>("copyRealSources") {
    from(rootDir.resolve("../../app/src/main/java")) {
        include(
            "agu/analys/bridge/AgentHttpServer.kt",
            "agu/analys/bridge/AgentOutputModels.kt",
            "agu/analys/bridge/AgentMarketModels.kt",
            "agu/analys/bridge/AgentTradingModels.kt"
        )
    }
    into(layout.buildDirectory.dir("real-src"))
}

sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("real-src")) }
tasks.named("compileKotlin") { dependsOn(copyRealSources) }

dependencies {
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}
application { mainClass.set("smoke.MainKt") }
