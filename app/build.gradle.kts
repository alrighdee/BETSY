import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

ktlint {
    android.set(true)
}

// Release signing comes from a gitignored keystore.properties beside this file. Absent it (a
// fresh clone, CI, anyone else building), the release variant falls back to unsigned, the build
// still succeeds, it just cannot produce an installable release APK.
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("../keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

// The one place the version is written. release-please rewrites the literal below when it cuts a
// release, the trailing marker is how it finds the line, so do not move or reword it. versionCode
// is derived from it so there is never a second number to remember to bump.
val betsyVersionName = "0.0.3" // x-release-please-version

val betsyVersionCode =
    betsyVersionName.substringBefore("-").split(".").let { (major, minor, patch) ->
        major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    }

// Short git identity burned into BuildConfig and drawn on the connect hero so a phone build can
// be matched to a tree without guessing. Repo root is the parent of `app/` (public/). Dirty trees
// get a trailing "+" so an uncommitted install is obvious.
fun gitShortIdentity(): String {
    fun run(vararg args: String): String =
        try {
            ProcessBuilder(*args)
                .directory(rootProject.projectDir.parentFile)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        } catch (_: Exception) {
            ""
        }
    val hash = run("git", "rev-parse", "--short", "HEAD").ifBlank { "unknown" }
    val dirty = run("git", "status", "--porcelain").isNotEmpty()
    return if (dirty && hash != "unknown") "$hash+" else hash
}

val betsyGitHash = gitShortIdentity()

// Wall-clock stamp of this Gradle configuration, local time. Rebuilds always refresh it so the
// hero can tell two installs of the same hash apart (dirty trees especially). Avoid java.time /
// java.util here: the Android Gradle DSL shadows the `java` package name.
val betsyBuildTime =
    try {
        ProcessBuilder("date", "+%Y-%m-%d %H:%M")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
            .ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }

android {
    namespace = "org.betsy"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.betsy"
        minSdk = 26
        targetSdk = 34
        versionCode = betsyVersionCode
        versionName = betsyVersionName
        buildConfigField("String", "GIT_HASH", "\"$betsyGitHash\"")
        buildConfigField("String", "BUILD_TIME", "\"$betsyBuildTime\"")
        // Hero overlay: "0.0.1 · a1b2c3d+ · <build time>"
        buildConfigField(
            "String",
            "BUILD_LABEL",
            "\"$betsyVersionName · $betsyGitHash · $betsyBuildTime\"",
        )
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file("../" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No shrinking: there is no third-party code to strip, and an obfuscated stack trace
            // from a tester is worth less than the few hundred KB it would save.
            isMinifyEnabled = false
            signingConfig =
                if (keystoreProps.getProperty("storeFile") != null) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws "not mocked" under plain JVM unit tests, so
    // anything calling JSONObject is untestable without the real implementation on the test
    // classpath. CaptureData.toJson is the single point where a capture leaves the device and
    // where VIN redaction runs, which is precisely the code that must be covered.
    testImplementation("org.json:json:20240303")
}

tasks.named("check") {
    dependsOn("ktlintCheck", "testDebugUnitTest", "lintDebug")
}
