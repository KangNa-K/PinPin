import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.pinpin.app"
    compileSdk = 37
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.pinpin.app"
        minSdk = 30
        targetSdk = 37
        versionCode = 14
        versionName = "1.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val releaseKeystoreDir = providers.gradleProperty("pinpin.keystoreDir").orNull
        ?.let(::File)
        ?: System.getenv("PINPIN_KEYSTORE_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".android-keys/pockref")
    val releaseKeystoreProperties = File(releaseKeystoreDir, "keystore.properties")
    val hasReleaseSigning = releaseKeystoreProperties.isFile

    signingConfigs {
        create("release") {
            // The signing directory is supplied by the pinpin.keystoreDir
            // Gradle property or PINPIN_KEYSTORE_DIR environment variable.
            // The historical ~/.android-keys/pockref fallback is retained for
            // the existing local setup, but signing is optional for CI.
            if (hasReleaseSigning) {
                val keystoreProps = Properties()
                releaseKeystoreProperties.inputStream().use { keystoreProps.load(it) }
                val storeFileName = keystoreProps.getProperty("storeFile")
                    ?: error("keystore.properties is missing storeFile")
                storeFile = File(storeFileName).let { file ->
                    if (file.isAbsolute) file else File(releaseKeystoreDir, storeFileName)
                }
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: error("keystore.properties is missing storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: error("keystore.properties is missing keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: error("keystore.properties is missing keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coil
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// JVM unit-test coverage. Instrumented tests are not included by Kover.
// Reports: ./gradlew koverHtmlReportDebug / koverXmlReportDebug
kover {
    reports {
        filters {
            excludes {
                annotatedBy("*Generated*")
                classes(
                    "*Hilt_*", "*_Factory*", "*_HiltModules*", "*_MembersInjector",
                    "dagger.hilt.*", "hilt_aggregated_deps.*", "*.BuildConfig",
                    "*ComposableSingletons*", "*_Impl*",
                )
            }
        }
    }
}
