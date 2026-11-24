import java.util.Properties
import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
// Optional build-time defaults live in android/secrets.properties (gitignored; see secrets.properties.example).
// None are required: a plain `./gradlew assembleDebug` builds an APK that starts in demo/onboarding mode,
// and the gateway URL and token are entered on the phone's settings page.
val secretsFile = rootProject.file("secrets.properties")
val gatewaySecrets = Properties().apply { if (secretsFile.exists()) secretsFile.inputStream().use { load(it) } }
// -PappId=com.example.hud installs a fork side by side with another build. The Kotlin namespace stays fixed.
val appId = (findProperty("appId") as String?)?.takeIf { it.isNotBlank() } ?: "io.github.rayneohud.companion"
// The access token is never compiled into the APK unless a private build asks for it with -PembedToken=true.
val embedToken = (findProperty("embedToken") as String?)?.toBoolean() == true
// Private builds can pass -PrequireGateway to refuse to assemble without a gateway URL (and token, if embedded).
if (project.hasProperty("requireGateway")) tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("install") }.configureEach {
    doFirst {
        check(gatewaySecrets.getProperty("GATEWAY_URL", "").isNotBlank() && (!embedToken || gatewaySecrets.getProperty("GATEWAY_TOKEN", "").isNotBlank())) {
            "android/secrets.properties has no GATEWAY_URL (or GATEWAY_TOKEN with -PembedToken=true)."
        }
    }
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
fun configString(name: String) = quoted(gatewaySecrets.getProperty(name, ""))
android {
    namespace = "io.lampyr.glasses"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = appId; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1"
        buildConfigField("String", "GATEWAY_URL", configString("GATEWAY_URL"))
        buildConfigField("String", "GATEWAY_TOKEN", if (embedToken) configString("GATEWAY_TOKEN") else quoted(""))
    }
    buildTypes.getByName("debug") {
        fun git(vararg args: String): String = runCatching {
            val process = ProcessBuilder(listOf("git") + args).directory(rootProject.rootDir).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) result else ""
        }.getOrDefault("")
        val sha = git("rev-parse", "--short", "HEAD").ifBlank { "unknown" } + if (git("status", "--porcelain").isNotBlank()) "-dirty" else ""
        buildConfigField("String", "GIT_SHA", "\"$sha\"")
        buildConfigField("String", "BUILD_TIME", "\"${Instant.now()}\"")
    }
    buildFeatures { buildConfig = true; aidl = true }
    // Keep the small UserService contract beside its implementation.
    sourceSets.getByName("main").aidl.srcDir("src/main/java")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true;unitTests.all { it.maxHeapSize="2g";it.systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2");it.testLogging {events("failed","skipped");showStandardStreams=true} } }
    packaging { resources.excludes += "META-INF/DEPENDENCIES" }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    testRuntimeOnly("com.microsoft.onnxruntime:onnxruntime:1.32.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
