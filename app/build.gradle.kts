import java.util.Properties
import java.util.Base64
import java.io.FileInputStream

val cachedSecrets = Properties()
var isSecretsLoaded = false

fun getSecret(key: String): String {
    if (!isSecretsLoaded) {
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            cachedSecrets.load(FileInputStream(envFile))
        } else {
            val exampleFile = rootProject.file(".env.example")
            if (exampleFile.exists()) {
                cachedSecrets.load(FileInputStream(exampleFile))
            }
        }
        isSecretsLoaded = true
    }
    return cachedSecrets.getProperty(key) ?: System.getenv(key) ?: project.findProperty(key)?.toString() ?: ""
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "agu.analys"
  compileSdk = 36
  defaultConfig {
    applicationId = "agu.analys"
    minSdk = 24
    targetSdk = 35
    versionCode = providers.gradleProperty("VERSION_CODE").map(String::toInt).getOrElse(94)
    versionName = "3.9.5"

    buildConfigField("String", "GEMINI_API_KEY", "\"${getSecret("GEMINI_API_KEY")}\"")
  }
  // Kunci rilis BERSIFAT OPSIONAL. Tanpa keystore, APK release tetap dibangun dan
  // ditandatangani memakai debug key bawaan AGP (~/.android/debug.keystore, dibuat
  // otomatis) supaya tetap bisa dipasang. Workflow lama mewajibkan secret sehingga
  // tidak pernah menghasilkan APK; aturan itu dihapus 2026-11-11.
  val envStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
  val envKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
  val envKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
  var envKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
  val envKeystoreBase64 = System.getenv("RELEASE_KEYSTORE_BASE64")

  if (!envKeystoreBase64.isNullOrBlank()) {
    val tempKeystore = file("${rootDir}/release_temp.keystore")
    if (!tempKeystore.exists()) {
      try {
        tempKeystore.writeBytes(Base64.getDecoder().decode(envKeystoreBase64.trim()))
      } catch (e: Exception) {
        System.err.println("Gagal mendekode RELEASE_KEYSTORE_BASE64: ${e.message}")
      }
    }
    if (tempKeystore.exists()) envKeystorePath = tempKeystore.absolutePath
  }

  val hasReleaseKeys = !envKeystorePath.isNullOrBlank() &&
    !envStorePassword.isNullOrBlank() && !envKeyAlias.isNullOrBlank() && !envKeyPassword.isNullOrBlank()

  signingConfigs {
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    if (hasReleaseKeys) {
      create("release") {
        storeFile = file(envKeystorePath!!)
        storePassword = envStorePassword!!
        keyAlias = envKeyAlias!!
        keyPassword = envKeyPassword!!
      }
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName(if (hasReleaseKeys) "release" else "debugConfig")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  // lint vital berjalan paralel dengan R8 dan memakan ~2 GiB tambahan. Runner CI
  // hanya punya ~7 GiB, sehingga kombinasi ini memicu OOM. Pemeriksaan lint tetap
  // bisa dijalankan eksplisit lewat `./gradlew :app:lint`.
  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  buildFeatures { compose = true; buildConfig = true }
  packaging {
    resources.excludes += setOf(
      "/META-INF/{AL2.0,LGPL2.1}",
      "META-INF/INDEX.LIST"
    )
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
  }
  sourceSets {
    getByName("main") {
      // Ekstensi OMP dibundel dari tools/oh-my-pi-extension (sumber kebenaran tunggal),
      // bukan disalin ke src/main/assets agar tidak jadi duplikat yang basi.
      assets.srcDir(File(layout.buildDirectory.get().asFile, "generated/omp-assets"))
    }
  }
}

val syncOmpExtensionAssets = tasks.register<Copy>("syncOmpExtensionAssets") {
  from(layout.projectDirectory.dir("../tools/oh-my-pi-extension")) {
    include("index.ts", "test-v2.sh", "APPLY_BRIDGE_V2.sh")
  }
  into(layout.buildDirectory.dir("generated/omp-assets/omp-extension"))
}
tasks.named("preBuild") { dependsOn(syncOmpExtensionAssets) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("DEEPSEEK_API_KEY")
  ignoreList.add("GEMINI_API_KEY")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.security.crypto)
  implementation(libs.timber)
  implementation(libs.coil.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.ta4j.core)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
  testImplementation(libs.mockk)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
