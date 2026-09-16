import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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
    compileSdk = 35

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
        minSdk = 31
        targetSdk = 35
        versionCode = 15
        versionName = "1.1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-websockets:2.3.8")
    implementation("io.ktor:ktor-client-okhttp:2.3.8")
    implementation("io.ktor:ktor-client-logging:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.media:media:1.7.0")
    
    // Scripting engine
    implementation("org.mozilla:rhino:1.7.15")
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
