import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.edge2.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edge2.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
        vectorDrawables { useSupportLibrary = true }
    }

    dependenciesInfo {
        // Disables dependency metadata in the APK.
        includeInApk = false
        // Disables dependency metadata in the App Bundle.
        includeInBundle = false
    }

    // Release signing: key provided by the environment (CI). Absent (F-Droid
    // build) → null signingConfig, F-Droid signs with its own key.
    val ksPath: String? = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val ksPresent = ksPath != null && file(ksPath).exists()
    signingConfigs {
        create("release") {
            if (ksPresent) {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (ksPresent) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // JVM tests: android.jar stubs return default values instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/{AL2.0,LGPL2.1}",
                // BouncyCastle signatures (sshj) — avoids packaging conflicts.
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
                "META-INF/BC*.RSA",
                "META-INF/versions/**",
            )
        }
    }
}

// Kotlin 2.2+: the `kotlinOptions` DSL is deprecated → `compilerOptions`.
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Embedded server + remote-control client.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.zxing.core)
    // Internet tunnel over SSH (localhost.run) — JVM stack → Android DNS works (4G).
    implementation(libs.sshj)
    implementation(libs.bcprov) // Full BouncyCastle (X25519 for the SSH key exchange)
    // sshj pulls bcpkix/bcutil 1.84 (CVE-2026-71889, CVE-2026-8763…) → forced to 1.86.
    implementation(libs.bcpkix)
    implementation(libs.bcutil)
    implementation(libs.slf4j.simple) // sshj logs → logcat (diagnostics)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
