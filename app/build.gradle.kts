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
val betsyVersionName = "0.0.2" // x-release-please-version

val betsyVersionCode =
    betsyVersionName.substringBefore("-").split(".").let { (major, minor, patch) ->
        major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
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
}

tasks.named("check") {
    dependsOn("ktlintCheck", "testDebugUnitTest", "lintDebug")
}
