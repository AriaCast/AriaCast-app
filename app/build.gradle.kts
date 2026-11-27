import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// Release signing secrets live in keystore.properties, a git-ignored file at the repo
// root (see .gitignore) - never in gradle.properties, which is tracked in version
// control. Populate it locally (or via CI secrets) with:
//   MYAPP_RELEASE_STORE_FILE=/path/to/release.keystore
//   MYAPP_RELEASE_STORE_PASSWORD=...
//   MYAPP_RELEASE_KEY_ALIAS=...
//   MYAPP_RELEASE_KEY_PASSWORD=...
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.aria.ariacast"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    // data/speakers.json is both the list of known speakers published for everyone and the copy
    // the app ships, so there is one file and no second copy to forget.
    sourceSets {
        getByName("main") { assets.srcDir(rootProject.file("data")) }
    }

    // SBC decoder for the AriaCompanion BT board (docs/ariacompanion-bt.md).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/sbc/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = keystoreProperties.getProperty("MYAPP_RELEASE_STORE_FILE")
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = keystoreProperties.getProperty("MYAPP_RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("MYAPP_RELEASE_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("MYAPP_RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.aria.ariacast"
        minSdk = 29
        targetSdk = 36
        versionCode = 18
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Debug builds (the CI artifact) install next to the real app as "AriaCast Test",
        // with their own data, and are signed with the debug key: no release key is needed.
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.getProperty("MYAPP_RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
        resValues = true
    }

    // "github": releases on GitHub/SafeHaven, with the in-app update check.
    // "fdroid": no self-update (F-Droid treats it as an anti-feature); updates come from F-Droid.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            resValue("bool", "self_update", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            resValue("bool", "self_update", "false")
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-websockets:2.3.13")
    implementation("io.ktor:ktor-client-okhttp:2.3.13")
    implementation("io.ktor:ktor-client-logging:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.media:media:1.7.0")
    
    // Scripting engine
    implementation("org.mozilla:rhino:1.7.15")
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    // Self-signed TLS certificates for the fake Google Cast receiver in CastClientTest.
    testImplementation(libs.bouncycastle.bcpkix)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
