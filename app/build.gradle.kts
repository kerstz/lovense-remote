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
        versionCode = 2
        versionName = "0.1.1"
        vectorDrawables { useSupportLibrary = true }
    }

    dependenciesInfo {
        // Désactive les métadonnées de dépendances dans l'APK.
        includeInApk = false
        // Désactive les métadonnées de dépendances dans l'App Bundle.
        includeInBundle = false
    }

    // Signature release : clé fournie par l'environnement (CI). Absente (build
    // F-Droid) → signingConfig nul, F-Droid signe avec sa propre clé.
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/{AL2.0,LGPL2.1}",
                // Signatures BouncyCastle (sshj) — évite les conflits de packaging.
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
                "META-INF/BC*.RSA",
                "META-INF/versions/**",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Phase 4 — serveur embarqué + client de contrôle à distance.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.zxing.core)
    // Tunnel internet par SSH (localhost.run) — stack JVM → DNS Android OK (4G).
    implementation(libs.sshj)
    implementation(libs.bcprov) // BouncyCastle complet (X25519 pour le KEX SSH)
    implementation(libs.slf4j.simple) // logs sshj → logcat (diagnostic)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
